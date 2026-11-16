package es.in2.issuanceportal.journey.infrastructure.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import es.in2.issuanceportal.TestcontainersConfiguration;
import es.in2.issuanceportal.journey.InMemoryIssuanceApi;
import es.in2.issuanceportal.journey.InMemoryOnboardingTickets;
import es.in2.issuanceportal.journey.api.JourneyProgress;
import es.in2.issuanceportal.journey.application.port.IssuanceApi;
import es.in2.issuanceportal.journey.application.port.IssuanceRejectedException;
import es.in2.issuanceportal.journey.application.port.IssuanceRequest;
import es.in2.issuanceportal.journey.application.port.IssuanceResult;
import es.in2.issuanceportal.journey.application.port.IssuanceUnavailableException;
import es.in2.issuanceportal.journey.application.port.OnboardingTicketException;
import es.in2.issuanceportal.shared.systemtoken.SystemTokenUnavailableException;
import es.in2.issuanceportal.session.api.EmailRecord;
import es.in2.issuanceportal.session.api.EmailVerificationRecord;
import es.in2.issuanceportal.session.EntitlementRecords;
import es.in2.issuanceportal.session.api.EntitlementRecord;
import es.in2.issuanceportal.session.api.HolderIdentity;
import es.in2.issuanceportal.session.api.IdentifierType;
import es.in2.issuanceportal.session.api.IssuanceRecord;
import es.in2.issuanceportal.session.api.JourneySessions;
import es.in2.issuanceportal.session.api.PersonalIdentifier;
import es.in2.issuanceportal.session.api.Section;
import es.in2.issuanceportal.shared.security.HolderRefs;
import es.in2.issuanceportal.shared.security.TestHolder;
import es.in2.issuanceportal.shared.tenant.TenantId;
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
import java.util.ArrayList;
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
    private static final String OTHER_EMAIL = "elena.other@example.test";
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
    private static final String WALLET_UNIT_SERVICE = "https://sandbox.127.0.0.1.nip.io:4443/business-wallet/api/v1";
    private static final String CONFIG_WITH_ONBOARDING = CONFIG.replace("\"onboarding\": {\"enabled\": false}",
            "\"onboarding\": {\"enabled\": true, \"wallet_unit_service_url\": \"" + WALLET_UNIT_SERVICE + "\"}");

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
                } catch (DataAccessException _) {
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

        @Bean
        @Primary
        InMemoryOnboardingTickets inMemoryOnboardingTickets(Clock clock) {
            return new InMemoryOnboardingTickets(clock);
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private JourneySessions sessions;
    @Autowired
    private HolderRefs holderRefs;
    @Autowired
    private JourneyProgress progress;
    @Autowired
    private IssuerGate issuer;
    @Autowired
    private InMemoryOnboardingTickets tickets;
    @Autowired
    private Clock clock;

    private ListAppender<ILoggingEvent> logs;
    private Logger rootLogger;

    @BeforeEach
    void setUp() {
        issuer.reset();
        tickets.reset();
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
        TestHolder holder = entitledJourney();

        MvcResult result = mvc.perform(confirmation(holder, "{\"email\":\"" + EMAIL + "\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.credential_offer_uri").value(
                        org.hamcrest.Matchers.startsWith("openid-credential-offer://?credential_offer_uri=")))
                .andExpect(jsonPath("$.expires_at").isNotEmpty())
                .andExpect(jsonPath("$.onboarding_url").doesNotExist())
                .andExpect(jsonPath("$.onboarding_expires_at").doesNotExist())
                .andReturn();
        assertThat(tickets.calls()).isEmpty();

        UUID process = jdbc.sql("SELECT issuance_process_id FROM \"sandbox_issuance_portal\".journey_issuance WHERE journey_id = :id")
                .param("id", journeyOf(holder)).query(UUID.class).single();
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
                        .with(holder.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("completed"))
                .andExpect(jsonPath("$.step").value("offer_obtained"));
    }

    @Test
    @DisplayName("AC-03 — postConfirmation_tenantWithOnboarding_answersTheOfferAndTheOnboardingAddress")
    void postConfirmation_tenantWithOnboarding_answersTheOfferAndTheOnboardingAddress() throws Exception {
        configure(CONFIG_WITH_ONBOARDING);
        TestHolder holder = entitledJourney();

        MvcResult result = mvc.perform(confirmation(holder, "{\"email\":\"" + EMAIL + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credential_offer_uri").value(
                        org.hamcrest.Matchers.startsWith("openid-credential-offer://?credential_offer_uri=")))
                .andExpect(jsonPath("$.expires_at").isNotEmpty())
                .andExpect(jsonPath("$.onboarding_url").value("https://sandbox.wallet.test/t/ticket-1"))
                .andExpect(jsonPath("$.onboarding_expires_at").isNotEmpty())
                .andReturn();

        com.fasterxml.jackson.databind.JsonNode answer = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsString());
        assertThat(tickets.calls()).singleElement().satisfies(call -> {
            assertThat(call.tenant()).isEqualTo(SANDBOX);
            assertThat(call.walletUnitServiceUrl()).isEqualTo(WALLET_UNIT_SERVICE);
            assertThat(call.email()).isEqualTo(EMAIL);
            assertThat(call.credentialOffer()).isEqualTo(answer.get("credential_offer_uri").asText());
        });
        UUID process = processOf(holder);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(process.toString());
        IssuanceRecord.Entry stored = IssuanceRecord.fromJson(sessions.read(SANDBOX, journeyOf(holder), Section.ISSUANCE).orElseThrow()).entry("r1").orElseThrow();
        assertThat(stored.onboarding()).contains("https://sandbox.wallet.test/t/ticket-1");
        assertThat(stored.offerUri()).contains(answer.get("credential_offer_uri").asText());
        mvc.perform(MockMvcRequestBuilders.get(CONTEXT + "/api/v1/journey").contextPath(CONTEXT).with(host())
                        .with(holder.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("completed"))
                .andExpect(jsonPath("$.step").value("offer_obtained"));
    }

    @Test
    @DisplayName("AC-11 — postConfirmation_tenantWithOnboarding_theAddressExpiresSixtySecondsBeforeTheOffer")
    void postConfirmation_tenantWithOnboarding_theAddressExpiresSixtySecondsBeforeTheOffer() throws Exception {
        configure(CONFIG_WITH_ONBOARDING);
        TestHolder holder = entitledJourney();

        MvcResult result = mvc.perform(confirmation(holder, "{\"email\":\"" + EMAIL + "\"}"))
                .andExpect(status().isOk()).andReturn();

        com.fasterxml.jackson.databind.JsonNode answer = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsString());
        java.time.Instant offerExpiresAt = java.time.Instant.parse(answer.get("expires_at").asText());
        java.time.Instant onboardingExpiresAt = java.time.Instant.parse(answer.get("onboarding_expires_at").asText());
        assertThat(onboardingExpiresAt).isBefore(offerExpiresAt);
        // The clock is the real one here: the exact figure is checked in IssuanceServiceTest.
        assertThat(tickets.calls().getFirst().expiresIn())
                .isBetween(java.time.Duration.ofSeconds(530), java.time.Duration.ofSeconds(540));
    }

    @Test
    @DisplayName("AC-12 — postConfirmation_ticketNotCreated_isOnboardingUnavailableWithoutOfferAndKeepsTheProcess")
    void postConfirmation_ticketNotCreated_isOnboardingUnavailableWithoutOfferAndKeepsTheProcess() throws Exception {
        configure(CONFIG_WITH_ONBOARDING);
        TestHolder holder = entitledJourney();
        tickets.failWith(OnboardingTicketException.Reason.UNAVAILABLE);

        MvcResult result = mvc.perform(confirmation(holder, "{\"email\":\"" + EMAIL + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("onboarding_unavailable"))
                .andExpect(jsonPath("$.credential_offer_uri").doesNotExist())
                .andExpect(jsonPath("$.onboarding_url").doesNotExist())
                .andReturn();

        UUID process = processOf(holder);
        assertThat(process).isNotNull();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("openid-credential-offer");
        Map<String, Object> row = jdbc.sql("SELECT state, step FROM \"sandbox_issuance_portal\".journey WHERE id = :id")
                .param("id", journeyOf(holder)).query().singleRow();
        assertThat(row).containsEntry("state", "in_progress")
                .containsEntry("step", "email_verified");
        IssuanceRecord.Entry stored = IssuanceRecord.fromJson(sessions.read(SANDBOX, journeyOf(holder), Section.ISSUANCE).orElseThrow()).entry("r1").orElseThrow();
        assertThat(stored.offerUri()).isEmpty();
        assertThat(stored.offerExpiresAt()).isNull();
        assertThat(stored.onboarding()).isEmpty();
        assertThat(stored.onboardingExpiresAt()).isNull();
        String everything = logs.list.stream().map(ConfirmationApiIT::describe).collect(Collectors.joining("\n"));
        assertThat(everything).contains("ISSUANCE_REQUESTED", "result=onboarding_failed",
                "ONBOARDING_TICKET_REQUESTED", "result=failed")
                .doesNotContain("result=issued", EMAIL, "openid-credential-offer");
    }

    @Test
    @DisplayName("AC-12 — postConfirmation_afterTheTicketFailed_isOnboardingUnavailableUntilTheRenewalExists")
    void postConfirmation_afterTheTicketFailed_isOnboardingUnavailableUntilTheRenewalExists() throws Exception {
        configure(CONFIG_WITH_ONBOARDING);
        TestHolder holder = entitledJourney();
        tickets.failWith(OnboardingTicketException.Reason.REJECTED);
        mvc.perform(confirmation(holder, "{\"email\":\"" + EMAIL + "\"}")).andExpect(status().isServiceUnavailable());
        tickets.recover();

        mvc.perform(confirmation(holder, "{\"email\":\"" + EMAIL + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("onboarding_unavailable"));

        assertThat(issuer.api().calls()).hasSize(1);
        assertThat(tickets.calls()).hasSize(1);
    }

    @Test
    @DisplayName("NFR-3 — postConfirmation_ticketSuccessAndFailure_leaveNoAddressOrPersonalDataInLogs")
    void postConfirmation_ticketSuccessAndFailure_leaveNoAddressOrPersonalDataInLogs() throws Exception {
        configure(CONFIG_WITH_ONBOARDING);
        String body = "{\"email\":\"" + EMAIL + "\"}";
        TestHolder failing = entitledJourney();
        tickets.failWith(OnboardingTicketException.Reason.NOT_AUTHORIZED);
        mvc.perform(confirmation(failing, body)).andExpect(status().isServiceUnavailable());
        tickets.recover();
        mvc.perform(confirmation(entitledJourney(), body)).andExpect(status().isOk());

        String everything = logs.list.stream().map(ConfirmationApiIT::describe).collect(Collectors.joining("\n"));
        assertThat(everything).contains("ONBOARDING_TICKET_REQUESTED", "result=created", "result=failed")
                .doesNotContain(EMAIL, "ticket-1", "wallet.test", "openid-credential-offer");
    }

    @Test
    @DisplayName("AC-02 — postConfirmation_bodyWithOtherFields_ignoresThemAndUsesTheJourneysData")
    void postConfirmation_bodyWithOtherFields_ignoresThemAndUsesTheJourneysData() throws Exception {
        TestHolder holder = entitledJourney();

        mvc.perform(confirmation(holder, "{\"email\":\"" + EMAIL + "\",\"givenName\":\"Mallory\","
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
        TestHolder holder = startedJourney();
        sessions.write(SANDBOX, journeyOf(holder), Section.IDENTITY, identity());

        mvc.perform(confirmation(holder, "{\"email\":\"" + EMAIL + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("step_not_allowed"));

        assertThat(issuer.api().calls()).isEmpty();
        assertThat(sessions.read(SANDBOX, journeyOf(holder), Section.ISSUANCE)).isEmpty();
        assertThat(sessions.read(SANDBOX, journeyOf(holder), Section.EMAIL)).isEmpty();
        assertThat(processOf(holder)).isNull();
    }

    @Test
    @DisplayName("AC-08 — postConfirmation_noVerifiedEmail_isStepNotAllowedWhateverTheBodySaysAndNoSystemIsCalled")
    void postConfirmation_noVerifiedEmail_isStepNotAllowedWhateverTheBodySaysAndNoSystemIsCalled() throws Exception {
        configure(CONFIG_WITH_ONBOARDING);
        TestHolder holder = entitledJourneyWithoutEmail();

        for (String body : new String[] {null, "{}", "{\"email\":\"" + EMAIL + "\"}", "{\"email\":12345}"}) {
            mvc.perform(confirmation(holder, body))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("step_not_allowed"));
        }

        assertThat(issuer.api().calls()).isEmpty();
        assertThat(tickets.calls()).isEmpty();
        assertThat(sessions.read(SANDBOX, journeyOf(holder), Section.ISSUANCE)).isEmpty();
        assertThat(sessions.read(SANDBOX, journeyOf(holder), Section.EMAIL)).isEmpty();
        assertThat(processOf(holder)).isNull();
    }

    @Test
    @DisplayName("AC-08 — postConfirmation_emailSectionWithoutVerification_isStepNotAllowedAndNothingIsRequested")
    void postConfirmation_emailSectionWithoutVerification_isStepNotAllowedAndNothingIsRequested() throws Exception {
        configure(CONFIG_WITH_ONBOARDING);
        TestHolder holder = entitledJourneyWithoutEmail();
        sessions.write(SANDBOX, journeyOf(holder), Section.EMAIL, new EmailRecord(EMAIL).toJson());

        mvc.perform(confirmation(holder, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("step_not_allowed"));

        assertNothingRequested(holder);
    }

    @Test
    @DisplayName("AC-08 — postConfirmation_emailNotVerifiedYet_isStepNotAllowedAndNothingIsRequested")
    void postConfirmation_emailNotVerifiedYet_isStepNotAllowedAndNothingIsRequested() throws Exception {
        configure(CONFIG_WITH_ONBOARDING);
        TestHolder holder = entitledJourneyWithoutEmail();
        sessions.write(SANDBOX, journeyOf(holder), Section.EMAIL, new EmailRecord(EMAIL).toJson());
        sessions.write(SANDBOX, journeyOf(holder), Section.EMAIL_VERIFICATION, new EmailVerificationRecord(EMAIL,
                "hash-of-a-pending-code", clock.instant().plusSeconds(600), 0, 1, clock.instant()).toJson());

        mvc.perform(confirmation(holder, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("step_not_allowed"));

        assertNothingRequested(holder);
    }

    @Test
    @DisplayName("AC-08 — postConfirmation_verifiedEmailIsNotTheSessionEmail_isStepNotAllowedAndNothingIsRequested")
    void postConfirmation_verifiedEmailIsNotTheSessionEmail_isStepNotAllowedAndNothingIsRequested() throws Exception {
        configure(CONFIG_WITH_ONBOARDING);
        TestHolder holder = entitledJourneyWithoutEmail();
        sessions.write(SANDBOX, journeyOf(holder), Section.EMAIL, new EmailRecord(EMAIL).toJson());
        sessions.write(SANDBOX, journeyOf(holder), Section.EMAIL_VERIFICATION, new EmailVerificationRecord(OTHER_EMAIL, null,
                null, 0, 1, clock.instant()).toJson());

        mvc.perform(confirmation(holder, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("step_not_allowed"));

        assertNothingRequested(holder);
    }

    @Test
    @DisplayName("AC-08 — postConfirmation_bodyWithAnotherEmail_isIgnoredAndTheVerifiedEmailIsUsed")
    void postConfirmation_bodyWithAnotherEmail_isIgnoredAndTheVerifiedEmailIsUsed() throws Exception {
        configure(CONFIG_WITH_ONBOARDING);
        TestHolder holder = entitledJourney();

        mvc.perform(confirmation(holder, "{\"email\":\"mallory@example.test\",\"givenName\":\"Mallory\"}"))
                .andExpect(status().isOk());

        assertThat(issuer.api().calls()).singleElement().satisfies(call ->
                assertThat(call.request().email()).isEqualTo(EMAIL));
        assertThat(tickets.calls()).singleElement().satisfies(call -> assertThat(call.email()).isEqualTo(EMAIL));
    }

    @Test
    @DisplayName("EC-05 — postConfirmation_retryAfterOnboardingUnavailable_keepsTheVerifiedEmailAndAsksForNoNewCode")
    void postConfirmation_retryAfterOnboardingUnavailable_keepsTheVerifiedEmailAndAsksForNoNewCode() throws Exception {
        configure(CONFIG_WITH_ONBOARDING);
        TestHolder holder = entitledJourney();
        tickets.failWith(OnboardingTicketException.Reason.UNAVAILABLE);
        mvc.perform(confirmation(holder, null)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("onboarding_unavailable"));
        tickets.recover();

        // The renewal of the offer of a process that already exists is EUD-295: until then the answer is the same
        // 503, but it is never step_not_allowed, because the verified email is still the journey's.
        mvc.perform(confirmation(holder, null)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("onboarding_unavailable"));

        assertThat(EmailRecord.fromJson(sessions.read(SANDBOX, journeyOf(holder), Section.EMAIL).orElseThrow()).address())
                .isEqualTo(EMAIL);
        assertThat(tickets.calls()).extracting(InMemoryOnboardingTickets.Call::email).containsOnly(EMAIL);
        assertThat(issuer.api().calls()).extracting(c -> c.request().email()).containsOnly(EMAIL);
    }

    @Test
    @DisplayName("ES-06 — postConfirmation_noJourney_isJourneyClosed")
    void postConfirmation_noJourney_isJourneyClosed() throws Exception {
        mvc.perform(post().contentType(MediaType.APPLICATION_JSON).content("{\"request_id\":\"r1\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.type").value("journey_closed"));
        mvc.perform(confirmation(TestHolder.of(SANDBOX, holderRefs), "{\"email\":\"" + EMAIL + "\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.type").value("journey_closed"));

        assertThat(issuer.api().calls()).isEmpty();
    }

    @Test
    @DisplayName("AC-25 — postConfirmation_requestAlreadyInOffer_keepsTheJourneyActiveAndAsksTheIssuerNoMore")
    void postConfirmation_requestAlreadyInOffer_keepsTheJourneyActiveAndAsksTheIssuerNoMore() throws Exception {
        TestHolder holder = entitledJourney();
        mvc.perform(confirmation(holder, "{\"email\":\"" + EMAIL + "\"}")).andExpect(status().isOk());

        // The journey is completed and active (AD-18): not closed. Until the new offer of EUD-359 task 4 the repeated
        // confirmation of a request that has its process answers onboarding_unavailable, and asks for nothing.
        mvc.perform(confirmation(holder, "{\"email\":\"" + EMAIL + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("onboarding_unavailable"));

        assertThat(issuer.api().calls()).hasSize(1);
        assertThat(jdbc.sql("SELECT state = 'completed' AND ended_at IS NULL FROM \"sandbox_issuance_portal\".journey "
                + "WHERE id = :id").param("id", journeyOf(holder)).query(Boolean.class).single()).isTrue();
    }

    @Test
    @DisplayName("ES-04 — postConfirmation_incompleteConfiguration_isConfigUnavailableAndNoSystemIsCalled")
    void postConfirmation_incompleteConfiguration_isConfigUnavailableAndNoSystemIsCalled() throws Exception {
        TestHolder holder = entitledJourney();
        configure(CONFIG.replace("\"onboarding\": {\"enabled\": false}", "\"onboarding\": {\"enabled\": true}"));

        mvc.perform(confirmation(holder, "{\"email\":\"" + EMAIL + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("config_unavailable"));
        configure(CONFIG.replace("\"attestation_type\": \"doctorid.sd.1\",", ""));
        mvc.perform(confirmation(holder, "{\"email\":\"" + EMAIL + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("config_unavailable"));

        assertThat(issuer.api().calls()).isEmpty();
        assertThat(processOf(holder)).isNull();
    }

    @Test
    @DisplayName("AC-05 — postConfirmation_noSystemToken_isIssuanceUnavailableAndTheJourneyStaysInProgress")
    void postConfirmation_noSystemToken_isIssuanceUnavailableAndTheJourneyStaysInProgress() throws Exception {
        TestHolder holder = entitledJourney();
        issuer.api().failWith(new SystemTokenUnavailableException(
                SystemTokenUnavailableException.Reason.NO_MACHINE_CREDENTIAL));

        mvc.perform(confirmation(holder, "{\"email\":\"" + EMAIL + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("issuance_unavailable"));

        assertInProgressWithEntitlement(holder);
    }

    @Test
    @DisplayName("ES-01 — postConfirmation_issuerRejects_isBadGatewayAndTheJourneyStaysInProgress")
    void postConfirmation_issuerRejects_isBadGatewayAndTheJourneyStaysInProgress() throws Exception {
        TestHolder holder = entitledJourney();
        issuer.api().failWith(new IssuanceRejectedException(IssuanceRejectedException.Reason.REQUEST_REJECTED));

        mvc.perform(confirmation(holder, "{\"email\":\"" + EMAIL + "\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value("issuance_rejected"));

        assertInProgressWithEntitlement(holder);
    }

    @Test
    @DisplayName("EC-02 — postConfirmation_afterIssuanceUnavailable_reusesTheKeyAndEndsWithOneProcess")
    void postConfirmation_afterIssuanceUnavailable_reusesTheKeyAndEndsWithOneProcess() throws Exception {
        TestHolder holder = entitledJourney();
        issuer.api().failWith(new IssuanceUnavailableException(IssuanceUnavailableException.Reason.NETWORK));
        mvc.perform(confirmation(holder, "{\"email\":\"" + EMAIL + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("issuance_unavailable"));
        assertInProgressWithEntitlement(holder);
        UUID keptKey = IssuanceRecord.fromJson(sessions.read(SANDBOX, journeyOf(holder), Section.ISSUANCE).orElseThrow()).entry("r1").orElseThrow().idempotencyKey();
        issuer.api().recover();

        mvc.perform(confirmation(holder, "{\"email\":\"" + EMAIL + "\"}")).andExpect(status().isOk());

        assertThat(issuer.api().calls()).extracting(InMemoryIssuanceApi.Call::idempotencyKey)
                .containsExactly(keptKey, keptKey);
        assertThat(issuer.api().processes()).isEqualTo(1);
        assertThat(processOf(holder)).isNotNull();
    }

    @Test
    @DisplayName("EC-02 — postConfirmation_retryWithTheSameVerifiedEmail_reusesTheKeyAndTheEmail")
    void postConfirmation_retryWithTheSameVerifiedEmail_reusesTheKeyAndTheEmail() throws Exception {
        TestHolder holder = entitledJourney();
        issuer.api().failWith(new IssuanceUnavailableException(IssuanceUnavailableException.Reason.NETWORK));
        mvc.perform(confirmation(holder, null)).andExpect(status().isServiceUnavailable());
        issuer.api().recover();

        mvc.perform(confirmation(holder, null)).andExpect(status().isOk());

        assertThat(issuer.api().calls()).extracting(c -> c.request().email()).containsExactly(EMAIL, EMAIL);
        assertThat(issuer.api().calls().get(1).idempotencyKey())
                .isEqualTo(issuer.api().calls().get(0).idempotencyKey());
        assertThat(issuer.api().processes()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-07 — postConfirmation_anotherEmailVerifiedBeforeAnyProcess_sendsANewKeyWithTheNewEmail")
    void postConfirmation_anotherEmailVerifiedBeforeAnyProcess_sendsANewKeyWithTheNewEmail() throws Exception {
        TestHolder holder = entitledJourney();
        issuer.api().failWith(new IssuanceUnavailableException(IssuanceUnavailableException.Reason.NETWORK));
        mvc.perform(confirmation(holder, null)).andExpect(status().isServiceUnavailable());
        issuer.api().recover();
        verifyEmail(holder, OTHER_EMAIL);

        mvc.perform(confirmation(holder, null)).andExpect(status().isOk());

        assertThat(issuer.api().calls()).extracting(c -> c.request().email()).containsExactly(EMAIL, OTHER_EMAIL);
        assertThat(issuer.api().calls().get(1).idempotencyKey())
                .isNotEqualTo(issuer.api().calls().get(0).idempotencyKey());
        IssuanceRecord.Entry stored = IssuanceRecord.fromJson(sessions.read(SANDBOX, journeyOf(holder), Section.ISSUANCE).orElseThrow()).entry("r1").orElseThrow();
        assertThat(stored.idempotencyKey()).isEqualTo(issuer.api().calls().get(1).idempotencyKey());
        assertThat(stored.isForEmail(OTHER_EMAIL)).isTrue();
        assertThat(processOf(holder)).isNotNull();
    }

    @Test
    @DisplayName("AC-07 — postConfirmation_issuanceRecordWithoutEmailAndNoProcess_sendsANewKey")
    void postConfirmation_issuanceRecordWithoutEmailAndNoProcess_sendsANewKey() throws Exception {
        TestHolder holder = entitledJourney();
        UUID legacyKey = UUID.randomUUID();
        sessions.write(SANDBOX, journeyOf(holder), Section.ISSUANCE,
                IssuanceRecord.empty().with("r1", IssuanceRecord.Entry.withKey(legacyKey, null)).toJson());

        mvc.perform(confirmation(holder, null)).andExpect(status().isOk());

        assertThat(issuer.api().calls()).singleElement().satisfies(call ->
                assertThat(call.idempotencyKey()).isNotEqualTo(legacyKey));
    }

    @Test
    @DisplayName("AC-07 — postConfirmation_anotherEmailVerifiedAfterTheProcessExists_keepsTheKeyAndTheProcess")
    void postConfirmation_anotherEmailVerifiedAfterTheProcessExists_keepsTheKeyAndTheProcess() throws Exception {
        configure(CONFIG_WITH_ONBOARDING);
        TestHolder holder = entitledJourney();
        tickets.failWith(OnboardingTicketException.Reason.UNAVAILABLE);
        mvc.perform(confirmation(holder, null)).andExpect(status().isServiceUnavailable());
        tickets.recover();
        UUID process = processOf(holder);
        UUID key = IssuanceRecord.fromJson(sessions.read(SANDBOX, journeyOf(holder), Section.ISSUANCE).orElseThrow()).entry("r1").orElseThrow().idempotencyKey();
        verifyEmail(holder, OTHER_EMAIL);

        // The renewal of the offer of a process that exists is EUD-295: until then the answer is onboarding_unavailable.
        mvc.perform(confirmation(holder, null)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("onboarding_unavailable"));

        assertThat(issuer.api().calls()).singleElement().satisfies(call ->
                assertThat(call.idempotencyKey()).isEqualTo(key));
        assertThat(issuer.api().processes()).isEqualTo(1);
        assertThat(processOf(holder)).isEqualTo(process);
        assertThat(IssuanceRecord.fromJson(sessions.read(SANDBOX, journeyOf(holder), Section.ISSUANCE).orElseThrow()).entry("r1").orElseThrow().idempotencyKey()).isEqualTo(key);
    }

    @Test
    @DisplayName("EC-04 — postConfirmation_processAlreadyRecorded_isOnboardingUnavailableWithoutAskingTheIssuer")
    void postConfirmation_processAlreadyRecorded_isOnboardingUnavailableWithoutAskingTheIssuer() throws Exception {
        TestHolder holder = entitledJourney();
        UUID process = UUID.randomUUID();
        jdbc.sql("INSERT INTO \"sandbox_issuance_portal\".journey_issuance (journey_id, request_id, issuance_process_id, created_at) "
                        + "VALUES (:id, 'r1', :process, now())")
                .param("process", process).param("id", journeyOf(holder)).update();

        mvc.perform(confirmation(holder, "{\"email\":\"" + EMAIL + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("onboarding_unavailable"));

        assertThat(issuer.api().calls()).isEmpty();
        assertThat(processOf(holder)).isEqualTo(process);
    }

    @Test
    @DisplayName("EC-03 — postConfirmation_issuerAsked_holdsNoLockOnTheJourneyRow")
    void postConfirmation_issuerAsked_holdsNoLockOnTheJourneyRow() throws Exception {
        TestHolder holder = entitledJourney();
        issuer.probe(journeyOf(holder));

        mvc.perform(confirmation(holder, "{\"email\":\"" + EMAIL + "\"}")).andExpect(status().isOk());

        assertThat(issuer.api().calls()).hasSize(1);
        assertThat(issuer.rowLockedWhileAsked).isFalse();
    }

    @Test
    @DisplayName("EC-03 — postConfirmation_twoAtTheSameTime_shareOneKeyAndEndWithOneProcess")
    void postConfirmation_twoAtTheSameTime_shareOneKeyAndEndWithOneProcess() throws Exception {
        TestHolder holder = entitledJourney();
        issuer.bothInFlight();
        String body = "{\"email\":\"" + EMAIL + "\"}";

        List<MvcResult> results;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<MvcResult> first = pool.submit(() -> mvc.perform(confirmation(holder, body)).andReturn());
            Future<MvcResult> second = pool.submit(() -> mvc.perform(confirmation(holder, body)).andReturn());
            results = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        }

        assertThat(results).allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
        assertThat(results.get(0).getResponse().getContentAsString())
                .isEqualTo(results.get(1).getResponse().getContentAsString());
        assertThat(issuer.api().calls()).hasSize(2);
        assertThat(issuer.api().calls().get(0).idempotencyKey()).isEqualTo(issuer.api().calls().get(1).idempotencyKey());
        assertThat(issuer.api().processes()).isEqualTo(1);
        assertThat(processOf(holder)).isNotNull();
        assertThat(jdbc.sql("SELECT count(*) FROM \"sandbox_issuance_portal\".journey WHERE id = :id AND state = 'completed'")
                .param("id", journeyOf(holder)).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    @DisplayName("NFR-3 — postConfirmation_successAndFailures_leaveNoPersonalDataOrOfferInLogs")
    void postConfirmation_successAndFailures_leaveNoPersonalDataOrOfferInLogs() throws Exception {
        String body = "{\"email\":\"" + EMAIL + "\"}";
        TestHolder failing = entitledJourney();
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
                "result=rejected", "result=system_token_unavailable", "COMPLETED")
                .doesNotContain(EMAIL, "not-an-email", GIVEN_NAME, FAMILY_NAME, DNI, ADMINISTRATIVE_NUMBER,
                "openid-credential-offer", "credential_offer_uri");
    }

    // --- helpers ---

    private void configure(String config) {
        jdbc.sql("INSERT INTO \"sandbox_issuance_portal\".portal_config (tenant, config) VALUES ('sandbox', CAST(:config AS jsonb)) "
                        + "ON CONFLICT (tenant) DO UPDATE SET config = EXCLUDED.config")
                .param("config", config)
                .update();
    }

    private void assertInProgressWithEntitlement(TestHolder holder) {
        Map<String, Object> row = jdbc.sql("SELECT state, step FROM \"sandbox_issuance_portal\".journey WHERE id = :id")
                .param("id", journeyOf(holder)).query().singleRow();
        assertThat(row).containsEntry("state", "in_progress")
                .containsEntry("step", "email_verified");
        assertThat(processOf(holder)).isNull();
        assertThat(sessions.read(SANDBOX, journeyOf(holder), Section.ENTITLEMENT)).isPresent();
        assertThat(IssuanceRecord.fromJson(sessions.read(SANDBOX, journeyOf(holder), Section.ISSUANCE).orElseThrow()).entry("r1").orElseThrow().offerUri())
                .isEmpty();
    }

    private void assertNothingRequested(TestHolder holder) {
        assertThat(issuer.api().calls()).isEmpty();
        assertThat(tickets.calls()).isEmpty();
        assertThat(sessions.read(SANDBOX, journeyOf(holder), Section.ISSUANCE)).isEmpty();
        assertThat(processOf(holder)).isNull();
    }

    /** The holder verifies another email with a code: both sections now carry it. */
    private void verifyEmail(TestHolder holder, String address) {
        sessions.write(SANDBOX, journeyOf(holder), Section.EMAIL, new EmailRecord(address).toJson());
        sessions.write(SANDBOX, journeyOf(holder), Section.EMAIL_VERIFICATION, new EmailVerificationRecord(address, null, null,
                0, 2, clock.instant()).toJson());
    }

    private UUID processOf(TestHolder holder) {
        return jdbc.sql("SELECT issuance_process_id FROM \"sandbox_issuance_portal\".journey_issuance WHERE journey_id = :id")
                .param("id", journeyOf(holder)).query(UUID.class).optional().orElse(null);
    }

    private UUID journeyOf(TestHolder holder) {
        return sessions.journeyOf(SANDBOX, holder.ref()).orElseThrow();
    }

    private TestHolder startedJourney() throws Exception {
        TestHolder holder = TestHolder.of(SANDBOX, holderRefs);
        mvc.perform(MockMvcRequestBuilders.post(CONTEXT + "/api/v1/journey")
                        .contextPath(CONTEXT)
                        .with(host())
                        .with(holder.token()))
                .andExpect(status().isCreated());
        return holder;
    }

    // --- EUD-359 task 3: one confirmation per request ---

    private static EntitlementRecord.Request requestOf(String id, EntitlementRecord.Status status, String number) {
        boolean carriesData = status.carriesData();
        return new EntitlementRecord.Request(id, "SIM-" + id, status, List.of(), false, "Hospital", null, null, null,
                carriesData ? Map.of("personal_administrative_number", number,
                        "medical_board", List.of("Colegio de Medicos de Prueba"),
                        "medical_speciality", List.of("Cardiology"), "legally_entitled", true) : Map.of(),
                carriesData ? new EntitlementRecord.Preview("DoctorID", List.of()) : null);
    }

    private void withRequests(TestHolder holder, EntitlementRecord.Request... requests) {
        sessions.write(SANDBOX, journeyOf(holder), Section.ENTITLEMENT, new EntitlementRecord(
                IdentifierType.PERSONAL_ID, null, List.of(requests), clock.instant()).toJson());
    }

    private static MockHttpServletRequestBuilder confirmationOf(TestHolder holder, String requestId) {
        return rawConfirmation(holder, "{\"request_id\":\"" + requestId + "\"}");
    }

    @Test
    @DisplayName("AC-05 — postConfirmation_twoRequests_twoProcessesWithDifferentKeysAndNoNewEmailVerification")
    void postConfirmation_twoRequests_twoProcessesWithDifferentKeysAndNoNewEmailVerification() throws Exception {
        TestHolder holder = entitledJourney();
        withRequests(holder, requestOf("r1", EntitlementRecord.Status.AVAILABLE, "280000001"),
                requestOf("r2", EntitlementRecord.Status.AVAILABLE, "280000002"));
        String verification = sessions.read(SANDBOX, journeyOf(holder), Section.EMAIL_VERIFICATION).orElseThrow();

        MvcResult first = mvc.perform(confirmationOf(holder, "r1")).andExpect(status().isOk()).andReturn();
        MvcResult second = mvc.perform(confirmationOf(holder, "r2")).andExpect(status().isOk()).andReturn();

        assertThat(first.getResponse().getContentAsString()).isNotEqualTo(second.getResponse().getContentAsString());
        assertThat(issuer.api().calls()).hasSize(2);
        assertThat(issuer.api().calls().get(0).idempotencyKey())
                .isNotEqualTo(issuer.api().calls().get(1).idempotencyKey());
        assertThat(jdbc.sql("SELECT request_id FROM \"sandbox_issuance_portal\".journey_issuance WHERE journey_id = :id "
                + "ORDER BY created_at, request_id").param("id", journeyOf(holder)).query(String.class).list())
                .containsExactly("r1", "r2");
        assertThat(jdbc.sql("SELECT count(DISTINCT issuance_process_id) FROM \"sandbox_issuance_portal\".journey_issuance "
                + "WHERE journey_id = :id").param("id", journeyOf(holder)).query(Long.class).single()).isEqualTo(2L);
        // The email was verified once for the whole journey: its verification is untouched.
        assertThat(sessions.read(SANDBOX, journeyOf(holder), Section.EMAIL_VERIFICATION)).contains(verification);
        IssuanceRecord section = IssuanceRecord.fromJson(
                sessions.read(SANDBOX, journeyOf(holder), Section.ISSUANCE).orElseThrow());
        assertThat(section.requests().keySet()).containsExactly("r1", "r2");
        assertThat(jdbc.sql("SELECT state = 'completed' AND completed_at IS NOT NULL AND ended_at IS NULL "
                + "FROM \"sandbox_issuance_portal\".journey WHERE id = :id")
                .param("id", journeyOf(holder)).query(Boolean.class).single()).isTrue();
    }

    @Test
    @DisplayName("EC-03 — postConfirmation_sameRequestAtTheSameTimeButAnotherOne_sharesTheKeyOfEachOnly")
    void postConfirmation_sameRequestAtTheSameTimeButAnotherOne_sharesTheKeyOfEachOnly() throws Exception {
        TestHolder holder = entitledJourney();
        withRequests(holder, requestOf("r1", EntitlementRecord.Status.AVAILABLE, "280000001"),
                requestOf("r2", EntitlementRecord.Status.AVAILABLE, "280000002"));
        CyclicBarrier together = new CyclicBarrier(3);
        List<Future<Integer>> answers = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String id : List.of("r1", "r1", "r2")) {
                answers.add(pool.submit(() -> {
                    together.await(5, TimeUnit.SECONDS);
                    return mvc.perform(confirmationOf(holder, id)).andReturn().getResponse().getStatus();
                }));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> answer : answers) {
                statuses.add(answer.get(20, TimeUnit.SECONDS));
            }
            // The duplicate of r1 answers the stored offer (200) or, if it arrives after the first finished, the
            // onboarding_unavailable of a request that has its process (until EUD-359 task 4): never a second process.
            assertThat(statuses).isSubsetOf(200, 503);
            assertThat(statuses.stream().filter(status -> status == 200).count()).isGreaterThanOrEqualTo(2L);
        }

        assertThat(jdbc.sql("SELECT count(*) FROM \"sandbox_issuance_portal\".journey_issuance WHERE journey_id = :id")
                .param("id", journeyOf(holder)).query(Long.class).single()).isEqualTo(2L);
        assertThat(issuer.api().calls().stream().map(call -> call.idempotencyKey()).distinct().count()).isEqualTo(2L);
        assertThat(issuer.api().processes()).isEqualTo(2);
    }

    @Test
    @DisplayName("ES-01 — postConfirmation_withoutRequestIdOrWithAnInvalidOne_is400AndNothingIsAsked")
    void postConfirmation_withoutRequestIdOrWithAnInvalidOne_is400AndNothingIsAsked() throws Exception {
        TestHolder holder = entitledJourney();

        for (String body : List.of("{}", "{\"request_id\":null}", "{\"request_id\":\"\"}", "{\"request_id\":\"  \"}",
                "{\"request_id\":\"" + "r".repeat(65) + "\"}", "{\"request_id\":12}", "[]", "not json",
                "{\"email\":\"" + EMAIL + "\"}")) {
            mvc.perform(rawConfirmation(holder, body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("bad_request"));
        }
        mvc.perform(post().with(holder.token())).andExpect(status().is4xxClientError());

        assertThat(issuer.api().calls()).isEmpty();
        assertThat(tickets.calls()).isEmpty();
    }

    @Test
    @DisplayName("AC-10 — postConfirmation_requestNotAvailableOrNotOfTheJourney_is409StepNotAllowedAndNothingIsAsked")
    void postConfirmation_requestNotAvailableOrNotOfTheJourney_is409StepNotAllowedAndNothingIsAsked() throws Exception {
        TestHolder holder = entitledJourney();
        withRequests(holder, requestOf("r1", EntitlementRecord.Status.IDENTITY_MISMATCH, "1"),
                requestOf("r2", EntitlementRecord.Status.UNDER_REVIEW, "2"),
                requestOf("r3", EntitlementRecord.Status.IDENTIFIER_MISMATCH, "3"),
                requestOf("r4", EntitlementRecord.Status.AVAILABLE, "4"));

        for (String id : List.of("r1", "r2", "r3", "r9", "r".repeat(64))) {
            mvc.perform(confirmationOf(holder, id))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("step_not_allowed"));
        }

        assertThat(issuer.api().calls()).isEmpty();
        assertThat(processOf(holder)).isNull();
    }

    @Test
    @DisplayName("AC-10 — postConfirmation_savedRequest_is409StepNotAllowedAndNothingIsAsked")
    void postConfirmation_savedRequest_is409StepNotAllowedAndNothingIsAsked() throws Exception {
        TestHolder holder = entitledJourney();
        withRequests(holder, requestOf("r1", EntitlementRecord.Status.AVAILABLE, "280000001"),
                requestOf("r2", EntitlementRecord.Status.AVAILABLE, "280000002"));
        mvc.perform(confirmationOf(holder, "r1")).andExpect(status().isOk());
        jdbc.sql("UPDATE \"sandbox_issuance_portal\".journey_issuance SET saved_at = now() WHERE journey_id = :id "
                + "AND request_id = 'r1'").param("id", journeyOf(holder)).update();

        mvc.perform(confirmationOf(holder, "r1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("step_not_allowed"));
        mvc.perform(confirmationOf(holder, "r2")).andExpect(status().isOk());

        assertThat(issuer.api().calls()).hasSize(2);
    }

    @Test
    @DisplayName("ES-08 — postConfirmation_endedJourney_is410JourneyClosed")
    void postConfirmation_endedJourney_is410JourneyClosed() throws Exception {
        TestHolder holder = entitledJourney();
        mvc.perform(confirmationOf(holder, "r1")).andExpect(status().isOk());
        jdbc.sql("UPDATE \"sandbox_issuance_portal\".journey SET ended_at = now() WHERE id = :id")
                .param("id", journeyOf(holder)).update();

        mvc.perform(confirmationOf(holder, "r1"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.type").value("journey_closed"));
    }

    /** What the holder has before confirming: the entitlement and the email verified with a code. */
    private TestHolder entitledJourney() throws Exception {
        TestHolder holder = entitledJourneyWithoutEmail();
        sessions.write(SANDBOX, journeyOf(holder), Section.EMAIL, new EmailRecord(EMAIL).toJson());
        sessions.write(SANDBOX, journeyOf(holder), Section.EMAIL_VERIFICATION, new EmailVerificationRecord(EMAIL, null, null, 0, 1,
                clock.instant()).toJson());
        jdbc.sql("UPDATE \"sandbox_issuance_portal\".journey SET step = 'email_verified' WHERE id = :id")
                .param("id", journeyOf(holder)).update();
        return holder;
    }

    /** What Identification and Authentic source leave in the journey before the holder verifies the email. */
    private TestHolder entitledJourneyWithoutEmail() throws Exception {
        TestHolder holder = startedJourney();
        sessions.write(SANDBOX, journeyOf(holder), Section.IDENTITY, identity());
        progress.reach(SANDBOX, holder.ref(), JourneyProgress.Step.IDENTIFIED);
        sessions.write(SANDBOX, journeyOf(holder), Section.ENTITLEMENT, EntitlementRecords.available(IdentifierType.PERSONAL_ID, Map.of(
                "personal_administrative_number", ADMINISTRATIVE_NUMBER,
                "medical_board", List.of("Colegio de Medicos de Prueba"),
                "medical_speciality", List.of("Cardiology"),
                "legally_entitled", true),
                new EntitlementRecord.Preview("DoctorID", List.of()), null, clock.instant()).toJson());
        progress.reach(SANDBOX, holder.ref(), JourneyProgress.Step.ENTITLEMENT_CONFIRMED);
        return holder;
    }

    private static String identity() {
        return new HolderIdentity(GIVEN_NAME, FAMILY_NAME, new PersonalIdentifier(IdentifierType.PERSONAL_ID, DNI),
                "elena.token@example.test").toJson();
    }

    /** The confirmation of {@code r1}: a body of other fields gets the request id added, as the interface sends it. */
    private static MockHttpServletRequestBuilder confirmation(TestHolder holder, String body) {
        String given = body == null ? "{}" : body;
        String withRequest = given.contains("request_id") ? given : given.trim().equals("{}")
                ? "{\"request_id\":\"r1\"}" : given.replaceFirst("[{]", "{\"request_id\":\"r1\",");
        return post().with(holder.token()).contentType(MediaType.APPLICATION_JSON).content(withRequest);
    }

    /** The body exactly as given, with no request id added. */
    private static MockHttpServletRequestBuilder rawConfirmation(TestHolder holder, String body) {
        return post().with(holder.token()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder post() {
        return MockMvcRequestBuilders.post(CONTEXT + "/api/v1/journey/confirmation")
                .contextPath(CONTEXT)
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
