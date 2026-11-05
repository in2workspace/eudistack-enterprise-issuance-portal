package es.in2.issuanceportal.journey.infrastructure.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import es.in2.issuanceportal.TestcontainersConfiguration;
import es.in2.issuanceportal.journey.InMemoryIssuanceApi;
import es.in2.issuanceportal.journey.api.JourneyProgress;
import es.in2.issuanceportal.journey.application.port.IssuanceApi;
import es.in2.issuanceportal.journey.application.port.IssuanceRejectedException;
import es.in2.issuanceportal.journey.application.port.IssuanceRequest;
import es.in2.issuanceportal.journey.application.port.IssuanceResult;
import es.in2.issuanceportal.journey.application.port.IssuanceUnavailableException;
import es.in2.issuanceportal.journey.application.port.SystemTokenUnavailableException;
import es.in2.issuanceportal.session.api.EntitlementRecord;
import es.in2.issuanceportal.session.api.HolderIdentity;
import es.in2.issuanceportal.session.api.IdentifierType;
import es.in2.issuanceportal.session.api.IssuanceRecord;
import es.in2.issuanceportal.session.api.JourneySessions;
import es.in2.issuanceportal.session.api.PersonalIdentifier;
import es.in2.issuanceportal.session.api.Section;
import es.in2.issuanceportal.session.api.SessionId;
import es.in2.issuanceportal.shared.tenant.TenantId;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /journey/confirmation} end to end against PostgreSQL, with the Issuer in the hands of the
 * test. The journey's row is locked for real, so the locking of EC-03 is checked in the database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ConfirmationApiIT.Issuer.class})
class ConfirmationApiIT {

    private static final String CONTEXT = "/issuance-portal";
    private static final String SANDBOX_HOST = "sandbox.127.0.0.1.nip.io";
    private static final TenantId SANDBOX = new TenantId("sandbox");
    private static final String EMAIL = "elena.ruiz@example.test";
    private static final String GIVEN_NAME = "Elena";
    private static final String FAMILY_NAME = "Ruiz Prieto";
    private static final String DNI = "00000001R";
    private static final String ADMINISTRATIVE_NUMBER = "280000001";
    private static final String CONFIG = """
            {"texts": {}, "legal_links": {}, "support": {}, "wallet_url": "https://sandbox.127.0.0.1.nip.io:4443/wallet",
             "attestation_type": "doctorid.sd.1",
             "data_placement": {
               "givenName": "identity.given_name",
               "familyName": "identity.family_name",
               "personal_administrative_number": "authentic_source.personal_administrative_number",
               "medical_board": "authentic_source.medical_board",
               "medical_speciality": "authentic_source.medical_speciality",
               "legally_entitled": "authentic_source.legally_entitled"},
             "onboarding": {"enabled": false}}""";

    /** The Issuer: answers like the real one to a repeated key, and can wait for a second request or look at the journey's row. */
    static final class IssuerGate implements IssuanceApi {

        private final Clock clock;
        private final JdbcClient jdbc;
        private volatile InMemoryIssuanceApi delegate;
        private volatile CyclicBarrier bothInFlight;
        private volatile UUID probedJourney;
        private final AtomicBoolean rowLockedWhileAsked = new AtomicBoolean();

        IssuerGate(Clock clock, JdbcClient jdbc) {
            this.clock = clock;
            this.jdbc = jdbc;
            reset();
        }

        void reset() {
            delegate = new InMemoryIssuanceApi(clock);
            bothInFlight = null;
            probedJourney = null;
            rowLockedWhileAsked.set(false);
        }

        InMemoryIssuanceApi api() {
            return delegate;
        }

        /** Every call waits for a second one: they only go on if two requests are with the Issuer together. */
        void bothInFlight() {
            bothInFlight = new CyclicBarrier(2);
        }

        /** Every call looks, from another connection, whether the journey's row is locked. */
        void probe(UUID journeyId) {
            probedJourney = journeyId;
        }

        @Override
        public IssuanceResult issue(TenantId tenant, UUID idempotencyKey, IssuanceRequest request) {
            CyclicBarrier barrier = bothInFlight;
            if (barrier != null) {
                try {
                    // Fails after ten seconds if the second request cannot reach the Issuer: the first holds a lock.
                    barrier.await(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("the two confirmations were not asked at the same time", e);
                }
            }
            if (probedJourney != null) {
                try {
                    jdbc.sql("SELECT id FROM " + tenant.schema() + ".journey WHERE id = :id FOR UPDATE NOWAIT")
                            .param("id", probedJourney).query(UUID.class).optional();
                } catch (DataAccessException e) {
                    rowLockedWhileAsked.set(true);
                }
            }
            return delegate.issue(tenant, idempotencyKey, request);
        }
    }

    @TestConfiguration
    static class Issuer {
        @Bean
        @Primary
        IssuerGate issuerGate(Clock clock, JdbcClient jdbc) {
            return new IssuerGate(clock, jdbc);
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private JourneySessions sessions;
    @Autowired
    private JourneyProgress progress;
    @Autowired
    private IssuerGate issuer;
    @Autowired
    private Clock clock;

    private ListAppender<ILoggingEvent> logs;
    private Logger rootLogger;

    @BeforeEach
    void setUp() {
        issuer.reset();
        configure(CONFIG);
        logs = new ListAppender<>();
        logs.start();
        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLogger.addAppender(logs);
    }

    /** The container is shared with the other integration tests, which expect no configuration. */
    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(logs);
        jdbc.sql("DELETE FROM \"sandbox_issuance_portal\".portal_config").update();
    }

    @Test
    @DisplayName("AC-03 — postConfirmation_entitledHolder_answersTheOfferAndCompletesTheJourney")
    void postConfirmation_entitledHolder_answersTheOfferAndCompletesTheJourney() throws Exception {
        SessionId session = entitledJourney();

        MvcResult result = mvc.perform(confirmation(session, "{\"email\":\"" + EMAIL + "\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.credential_offer_uri").value(
                        org.hamcrest.Matchers.startsWith("openid-credential-offer://?credential_offer_uri=")))
                .andExpect(jsonPath("$.expires_at").isNotEmpty())
                .andExpect(jsonPath("$.onboarding_url").doesNotExist())
                .andReturn();

        UUID process = jdbc.sql("SELECT issuance_process_id FROM \"sandbox_issuance_portal\".journey WHERE id = :id")
                .param("id", journeyOf(session)).query(UUID.class).single();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(process.toString());
        assertThat(issuer.api().calls()).hasSize(1);
        IssuanceRequest request = issuer.api().calls().getFirst().request();
        assertThat(request.credentialConfigurationId()).isEqualTo("doctorid.sd.1");
        assertThat(request.email()).isEqualTo(EMAIL);
        assertThat(request.delivery()).isEqualTo("ui");
        assertThat(request.grantType()).isEqualTo("authorization_code");
        assertThat(request.payload()).containsEntry("givenName", GIVEN_NAME).containsEntry("familyName", FAMILY_NAME)
                .containsEntry("personal_administrative_number", ADMINISTRATIVE_NUMBER)
                .containsEntry("legally_entitled", true)
                .doesNotContainKeys("email", "dni", "country");
        mvc.perform(MockMvcRequestBuilders.get(CONTEXT + "/api/v1/journey").contextPath(CONTEXT).with(host())
                        .cookie(new Cookie("journey_session", session.value())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("completed"))
                .andExpect(jsonPath("$.step").value("offer_obtained"));
    }

    @Test
    @DisplayName("AC-02 — postConfirmation_bodyWithOtherFields_ignoresThemAndUsesTheJourneysData")
    void postConfirmation_bodyWithOtherFields_ignoresThemAndUsesTheJourneysData() throws Exception {
        SessionId session = entitledJourney();

        mvc.perform(confirmation(session, "{\"email\":\"" + EMAIL + "\",\"givenName\":\"Mallory\","
                        + "\"personal_administrative_number\":\"999999999\",\"legally_entitled\":false}"))
                .andExpect(status().isOk());

        assertThat(issuer.api().calls().getFirst().request().payload())
                .containsEntry("givenName", GIVEN_NAME)
                .containsEntry("personal_administrative_number", ADMINISTRATIVE_NUMBER)
                .containsEntry("legally_entitled", true);
    }

    @Test
    @DisplayName("AC-01 — postConfirmation_noConfirmedEntitlement_isStepNotAllowedAndNothingChanges")
    void postConfirmation_noConfirmedEntitlement_isStepNotAllowedAndNothingChanges() throws Exception {
        SessionId session = startedJourney();
        sessions.write(SANDBOX, session, Section.IDENTITY, identity());

        mvc.perform(confirmation(session, "{\"email\":\"" + EMAIL + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("step_not_allowed"));

        assertThat(issuer.api().calls()).isEmpty();
        assertThat(sessions.read(SANDBOX, session, Section.ISSUANCE)).isEmpty();
        assertThat(sessions.read(SANDBOX, session, Section.EMAIL)).isEmpty();
        assertThat(processOf(session)).isNull();
    }

    @Test
    @DisplayName("EC-01 — postConfirmation_missingOrInvalidEmail_isBadRequestAndNoSystemIsCalled")
    void postConfirmation_missingOrInvalidEmail_isBadRequestAndNoSystemIsCalled() throws Exception {
        SessionId session = entitledJourney();

        mvc.perform(confirmation(session, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("email_required"));
        mvc.perform(confirmation(session, "{}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("email_required"));
        mvc.perform(confirmation(session, "{\"email\":\"\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("email_required"));
        mvc.perform(confirmation(session, "{\"email\":null}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("email_required"));
        mvc.perform(confirmation(session, "{\"email\":\"elena.example.test\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("email_invalid"));
        mvc.perform(confirmation(session, "{\"email\":12345}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("email_invalid"));

        assertThat(issuer.api().calls()).isEmpty();
        assertThat(sessions.read(SANDBOX, session, Section.ISSUANCE)).isEmpty();
        assertThat(processOf(session)).isNull();
    }

    @Test
    @DisplayName("ES-06 — postConfirmation_noJourney_isJourneyClosed")
    void postConfirmation_noJourney_isJourneyClosed() throws Exception {
        mvc.perform(post().contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + EMAIL + "\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.type").value("journey_closed"));
        mvc.perform(confirmation(SessionId.generate(), "{\"email\":\"" + EMAIL + "\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.type").value("journey_closed"));

        assertThat(issuer.api().calls()).isEmpty();
    }

    @Test
    @DisplayName("EC-09 — postConfirmation_completedJourney_isJourneyClosedAndNoSystemIsCalled")
    void postConfirmation_completedJourney_isJourneyClosedAndNoSystemIsCalled() throws Exception {
        SessionId session = entitledJourney();
        mvc.perform(confirmation(session, "{\"email\":\"" + EMAIL + "\"}")).andExpect(status().isOk());

        mvc.perform(confirmation(session, "{\"email\":\"" + EMAIL + "\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.type").value("journey_closed"));

        assertThat(issuer.api().calls()).hasSize(1);
    }

    @Test
    @DisplayName("ES-04 — postConfirmation_incompleteConfiguration_isConfigUnavailableAndNoSystemIsCalled")
    void postConfirmation_incompleteConfiguration_isConfigUnavailableAndNoSystemIsCalled() throws Exception {
        SessionId session = entitledJourney();
        configure(CONFIG.replace("\"onboarding\": {\"enabled\": false}", "\"onboarding\": {\"enabled\": true}"));

        mvc.perform(confirmation(session, "{\"email\":\"" + EMAIL + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("config_unavailable"));
        configure(CONFIG.replace("\"attestation_type\": \"doctorid.sd.1\",", ""));
        mvc.perform(confirmation(session, "{\"email\":\"" + EMAIL + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("config_unavailable"));

        assertThat(issuer.api().calls()).isEmpty();
        assertThat(processOf(session)).isNull();
    }

    @Test
    @DisplayName("AC-05 — postConfirmation_noSystemToken_isIssuanceUnavailableAndTheJourneyStaysInProgress")
    void postConfirmation_noSystemToken_isIssuanceUnavailableAndTheJourneyStaysInProgress() throws Exception {
        SessionId session = entitledJourney();
        issuer.api().failWith(new SystemTokenUnavailableException(
                SystemTokenUnavailableException.Reason.NO_MACHINE_CREDENTIAL));

        mvc.perform(confirmation(session, "{\"email\":\"" + EMAIL + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("issuance_unavailable"));

        assertInProgressWithEntitlement(session);
    }

    @Test
    @DisplayName("ES-01 — postConfirmation_issuerRejects_isBadGatewayAndTheJourneyStaysInProgress")
    void postConfirmation_issuerRejects_isBadGatewayAndTheJourneyStaysInProgress() throws Exception {
        SessionId session = entitledJourney();
        issuer.api().failWith(new IssuanceRejectedException(IssuanceRejectedException.Reason.REQUEST_REJECTED));

        mvc.perform(confirmation(session, "{\"email\":\"" + EMAIL + "\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value("issuance_rejected"));

        assertInProgressWithEntitlement(session);
    }

    @Test
    @DisplayName("EC-02 — postConfirmation_afterIssuanceUnavailable_reusesTheKeyAndEndsWithOneProcess")
    void postConfirmation_afterIssuanceUnavailable_reusesTheKeyAndEndsWithOneProcess() throws Exception {
        SessionId session = entitledJourney();
        issuer.api().failWith(new IssuanceUnavailableException(IssuanceUnavailableException.Reason.NETWORK));
        mvc.perform(confirmation(session, "{\"email\":\"" + EMAIL + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("issuance_unavailable"));
        assertInProgressWithEntitlement(session);
        UUID keptKey = IssuanceRecord.fromJson(sessions.read(SANDBOX, session, Section.ISSUANCE).orElseThrow())
                .idempotencyKey();
        issuer.api().recover();

        mvc.perform(confirmation(session, "{\"email\":\"" + EMAIL + "\"}")).andExpect(status().isOk());

        assertThat(issuer.api().calls()).extracting(InMemoryIssuanceApi.Call::idempotencyKey)
                .containsExactly(keptKey, keptKey);
        assertThat(issuer.api().processes()).isEqualTo(1);
        assertThat(processOf(session)).isNotNull();
    }

    @Test
    @DisplayName("EC-02 — postConfirmation_retryWithAnotherEmail_sendsTheFirstEmailWithTheSameKey")
    void postConfirmation_retryWithAnotherEmail_sendsTheFirstEmailWithTheSameKey() throws Exception {
        SessionId session = entitledJourney();
        issuer.api().failWith(new IssuanceUnavailableException(IssuanceUnavailableException.Reason.NETWORK));
        mvc.perform(confirmation(session, "{\"email\":\"" + EMAIL + "\"}")).andExpect(status().isServiceUnavailable());
        issuer.api().recover();

        mvc.perform(confirmation(session, "{\"email\":\"other.address@example.test\"}")).andExpect(status().isOk());

        assertThat(issuer.api().calls()).extracting(c -> c.request().email()).containsExactly(EMAIL, EMAIL);
        assertThat(issuer.api().calls().get(1).idempotencyKey())
                .isEqualTo(issuer.api().calls().get(0).idempotencyKey());
        assertThat(issuer.api().processes()).isEqualTo(1);
    }

    @Test
    @DisplayName("EC-04 — postConfirmation_processAlreadyRecorded_isOnboardingUnavailableWithoutAskingTheIssuer")
    void postConfirmation_processAlreadyRecorded_isOnboardingUnavailableWithoutAskingTheIssuer() throws Exception {
        SessionId session = entitledJourney();
        UUID process = UUID.randomUUID();
        jdbc.sql("UPDATE \"sandbox_issuance_portal\".journey SET issuance_process_id = :process WHERE id = :id")
                .param("process", process).param("id", journeyOf(session)).update();

        mvc.perform(confirmation(session, "{\"email\":\"" + EMAIL + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("onboarding_unavailable"));

        assertThat(issuer.api().calls()).isEmpty();
        assertThat(processOf(session)).isEqualTo(process);
    }

    @Test
    @DisplayName("EC-03 — postConfirmation_issuerAsked_holdsNoLockOnTheJourneyRow")
    void postConfirmation_issuerAsked_holdsNoLockOnTheJourneyRow() throws Exception {
        SessionId session = entitledJourney();
        issuer.probe(journeyOf(session));

        mvc.perform(confirmation(session, "{\"email\":\"" + EMAIL + "\"}")).andExpect(status().isOk());

        assertThat(issuer.api().calls()).hasSize(1);
        assertThat(issuer.rowLockedWhileAsked).isFalse();
    }

    @Test
    @DisplayName("EC-03 — postConfirmation_twoAtTheSameTime_shareOneKeyAndEndWithOneProcess")
    void postConfirmation_twoAtTheSameTime_shareOneKeyAndEndWithOneProcess() throws Exception {
        SessionId session = entitledJourney();
        issuer.bothInFlight();
        String body = "{\"email\":\"" + EMAIL + "\"}";

        List<MvcResult> results;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<MvcResult> first = pool.submit(() -> mvc.perform(confirmation(session, body)).andReturn());
            Future<MvcResult> second = pool.submit(() -> mvc.perform(confirmation(session, body)).andReturn());
            results = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        }

        assertThat(results).allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
        assertThat(results.get(0).getResponse().getContentAsString())
                .isEqualTo(results.get(1).getResponse().getContentAsString());
        assertThat(issuer.api().calls()).hasSize(2);
        assertThat(issuer.api().calls().get(0).idempotencyKey()).isEqualTo(issuer.api().calls().get(1).idempotencyKey());
        assertThat(issuer.api().processes()).isEqualTo(1);
        assertThat(processOf(session)).isNotNull();
        assertThat(jdbc.sql("SELECT count(*) FROM \"sandbox_issuance_portal\".journey WHERE id = :id AND state = 'completed'")
                .param("id", journeyOf(session)).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    @DisplayName("NFR-3 — postConfirmation_successAndFailures_leaveNoPersonalDataOrOfferInLogs")
    void postConfirmation_successAndFailures_leaveNoPersonalDataOrOfferInLogs() throws Exception {
        String body = "{\"email\":\"" + EMAIL + "\"}";
        SessionId failing = entitledJourney();
        for (RuntimeException failure : List.<RuntimeException>of(
                new IssuanceUnavailableException(IssuanceUnavailableException.Reason.SERVER_ERROR),
                new IssuanceRejectedException(IssuanceRejectedException.Reason.UNEXPECTED_RESPONSE),
                new SystemTokenUnavailableException(SystemTokenUnavailableException.Reason.VERIFIER_REJECTED))) {
            issuer.api().failWith(failure);
            mvc.perform(confirmation(failing, body));
        }
        mvc.perform(confirmation(failing, "{\"email\":\"not-an-email\"}"));
        issuer.api().recover();
        mvc.perform(confirmation(failing, body)).andExpect(status().isOk());

        String everything = logs.list.stream().map(ConfirmationApiIT::describe).collect(Collectors.joining("\n"));
        assertThat(logs.list).isNotEmpty();
        assertThat(everything).contains("ISSUANCE_REQUESTED", "result=issued", "result=unavailable",
                "result=rejected", "result=system_token_unavailable", "COMPLETED");
        assertThat(everything).doesNotContain(EMAIL, "not-an-email", GIVEN_NAME, FAMILY_NAME, DNI, ADMINISTRATIVE_NUMBER,
                "openid-credential-offer", "credential_offer_uri");
    }

    // --- helpers ---

    private void configure(String config) {
        jdbc.sql("INSERT INTO \"sandbox_issuance_portal\".portal_config (tenant, config) VALUES ('sandbox', CAST(:config AS jsonb)) "
                        + "ON CONFLICT (tenant) DO UPDATE SET config = EXCLUDED.config")
                .param("config", config)
                .update();
    }

    private void assertInProgressWithEntitlement(SessionId session) {
        Map<String, Object> row = jdbc.sql("SELECT state, step, issuance_process_id FROM \"sandbox_issuance_portal\".journey WHERE id = :id")
                .param("id", journeyOf(session)).query().singleRow();
        assertThat(row.get("state")).isEqualTo("in_progress");
        assertThat(row.get("step")).isEqualTo("entitlement_confirmed");
        assertThat(row.get("issuance_process_id")).isNull();
        assertThat(sessions.read(SANDBOX, session, Section.ENTITLEMENT)).isPresent();
        assertThat(IssuanceRecord.fromJson(sessions.read(SANDBOX, session, Section.ISSUANCE).orElseThrow()).offerUri())
                .isEmpty();
    }

    private UUID processOf(SessionId session) {
        return jdbc.sql("SELECT issuance_process_id FROM \"sandbox_issuance_portal\".journey WHERE id = :id")
                .param("id", journeyOf(session)).query(UUID.class).optional().orElse(null);
    }

    private UUID journeyOf(SessionId session) {
        return sessions.journeyOf(SANDBOX, session).orElseThrow();
    }

    private SessionId startedJourney() throws Exception {
        Cookie cookie = mvc.perform(MockMvcRequestBuilders.post(CONTEXT + "/api/v1/journey")
                        .contextPath(CONTEXT)
                        .header("X-Requested-With", "issuance-portal")
                        .with(host()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getCookie("journey_session");
        return SessionId.parse(cookie.getValue()).orElseThrow();
    }

    /** What Identification and Authentic source leave in the journey before the holder confirms. */
    private SessionId entitledJourney() throws Exception {
        SessionId session = startedJourney();
        sessions.write(SANDBOX, session, Section.IDENTITY, identity());
        progress.reach(SANDBOX, session, JourneyProgress.Step.IDENTIFIED);
        sessions.write(SANDBOX, session, Section.ENTITLEMENT, new EntitlementRecord(IdentifierType.DNI, Map.of(
                "personal_administrative_number", ADMINISTRATIVE_NUMBER,
                "medical_board", List.of("Colegio de Medicos de Prueba"),
                "medical_speciality", List.of("Cardiology"),
                "legally_entitled", true),
                new EntitlementRecord.Preview("DoctorID", List.of(), null, true), clock.instant()).toJson());
        progress.reach(SANDBOX, session, JourneyProgress.Step.ENTITLEMENT_CONFIRMED);
        return session;
    }

    private static String identity() {
        return new HolderIdentity(GIVEN_NAME, FAMILY_NAME, new PersonalIdentifier(IdentifierType.DNI, DNI),
                "elena.token@example.test").toJson();
    }

    private static MockHttpServletRequestBuilder confirmation(SessionId session, String body) {
        MockHttpServletRequestBuilder request = post().cookie(new Cookie("journey_session", session.value()));
        return body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder post() {
        return MockMvcRequestBuilders.post(CONTEXT + "/api/v1/journey/confirmation")
                .contextPath(CONTEXT)
                .header("X-Requested-With", "issuance-portal")
                .with(host());
    }

    private static RequestPostProcessor host() {
        return request -> {
            request.setServerName(SANDBOX_HOST);
            return request;
        };
    }

    private static String describe(ILoggingEvent event) {
        return event.getFormattedMessage() + describe(event.getThrowableProxy());
    }

    private static String describe(IThrowableProxy throwable) {
        return throwable == null ? "" : throwable.getClassName() + ":" + throwable.getMessage()
                + describe(throwable.getCause());
    }
}
