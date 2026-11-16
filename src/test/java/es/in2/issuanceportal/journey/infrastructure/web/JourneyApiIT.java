package es.in2.issuanceportal.journey.infrastructure.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import es.in2.issuanceportal.TestcontainersConfiguration;
import es.in2.issuanceportal.journey.api.JourneyProgress;
import es.in2.issuanceportal.journey.api.JourneyWithdrawal;
import es.in2.issuanceportal.journey.application.JourneyService;
import es.in2.issuanceportal.session.EntitlementRecords;
import es.in2.issuanceportal.session.api.EntitlementRecord;
import es.in2.issuanceportal.session.api.HolderIdentity;
import es.in2.issuanceportal.session.api.IdentifierType;
import es.in2.issuanceportal.session.api.IssuanceRecord;
import es.in2.issuanceportal.session.api.JourneySessions;
import es.in2.issuanceportal.session.api.PersonalIdentifier;
import es.in2.issuanceportal.session.api.Section;
import es.in2.issuanceportal.shared.security.AccessTokens;
import es.in2.issuanceportal.shared.security.HolderRefs;
import es.in2.issuanceportal.shared.security.TestHolder;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The journey API end to end, against PostgreSQL, with the journey bound to the holder of the token (ac.md of EUD-314). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class JourneyApiIT {

    private static final String CONTEXT = "/issuance-portal";
    private static final String API = CONTEXT + "/api/v1";
    private static final String JOURNEY = API + "/journey";
    private static final String SANDBOX_HOST = "sandbox.127.0.0.1.nip.io";
    private static final String KPMG_HOST = "kpmg.127.0.0.1.nip.io";
    private static final TenantId SANDBOX = new TenantId("sandbox");
    private static final TenantId KPMG = new TenantId("kpmg");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private JourneySessions sessions;
    @Autowired
    private JourneyProgress progress;
    @Autowired
    private JourneyWithdrawal withdrawal;
    @Autowired
    private JourneyService journeyService;
    @Autowired
    private HolderRefs holderRefs;

    private ListAppender<ILoggingEvent> logs;
    private final Map<String, Level> previousLevels = new LinkedHashMap<>();

    @BeforeEach
    void captureLogs() {
        // The most verbose log there can be: what must not be in the log is asserted against it.
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logs);
        for (String name : List.of("es.in2.issuanceportal", "org.springframework.security")) {
            Logger logger = (Logger) LoggerFactory.getLogger(name);
            previousLevels.put(name, logger.getLevel());
            logger.setLevel(Level.DEBUG);
        }
    }

    @AfterEach
    void releaseLogs() {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
        previousLevels.forEach((name, level) -> ((Logger) LoggerFactory.getLogger(name)).setLevel(level));
        previousLevels.clear();
    }

    // --- AC-02, AC-10: the journey is opened for the holder of the token, once ---

    @Test
    @DisplayName("AC-02 — postJourney_validToken_createsJourneyInProgressBoundToTheHolder")
    void postJourney_validToken_createsJourneyInProgressBoundToTheHolder() throws Exception {
        TestHolder holder = newHolder();

        mvc.perform(open(holder))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("in_progress"))
                .andExpect(jsonPath("$.step").value("identified"))
                .andExpect(jsonPath("$.withdrawal_reason").doesNotExist());

        UUID journeyId = journeyOf(holder);
        assertThat(journeyRow(journeyId)).containsEntry("state", "in_progress")
                .containsEntry("identification_method", "certificate");
        assertThat(jdbc.sql("SELECT holder_ref FROM \"sandbox_issuance_portal\".journey_session WHERE journey_id = :j")
                .param("j", journeyId).query(String.class).single()).isEqualTo(holder.ref().value());
        HolderIdentity identity = HolderIdentity.fromJson(sessions.read(SANDBOX, journeyId, Section.IDENTITY).orElseThrow());
        assertThat(identity.givenName()).isEqualTo(AccessTokens.GIVEN_NAME);
        assertThat(identity.familyName()).isEqualTo(AccessTokens.FAMILY_NAME);
        assertThat(identity.personalIdentifier()).isEqualTo(
                new PersonalIdentifier(IdentifierType.PERSONAL_ID, AccessTokens.PERSONAL_NUMBER));
    }

    @Test
    @DisplayName("AC-02 — postJourney_attestationLogin_recordsTheAttestationMethodAndTheColegiadoType")
    void postJourney_attestationLogin_recordsTheAttestationMethodAndTheColegiadoType() throws Exception {
        TestHolder holder = newHolder();

        mvc.perform(MockMvcRequestBuilders.post(JOURNEY).contextPath(CONTEXT).with(host(SANDBOX_HOST))
                        .with(holder.token(tokens -> tokens.claim("credential_type", "doctorid.sd.1").without("amr"))))
                .andExpect(status().isCreated());

        UUID journeyId = journeyOf(holder);
        assertThat(journeyRow(journeyId)).containsEntry("identification_method", "attestation");
        assertThat(HolderIdentity.fromJson(sessions.read(SANDBOX, journeyId, Section.IDENTITY).orElseThrow())
                .personalIdentifier().type()).isEqualTo(IdentifierType.COLEGIADO);
    }

    @Test
    @DisplayName("AC-10 — postJourney_holderWithActiveJourney_returnsItAndDoesNotRewriteTheIdentity")
    void postJourney_holderWithActiveJourney_returnsItAndDoesNotRewriteTheIdentity() throws Exception {
        TestHolder holder = newHolder();
        mvc.perform(open(holder)).andExpect(status().isCreated());
        UUID journeyId = journeyOf(holder);
        progress.reach(SANDBOX, holder.ref(), JourneyProgress.Step.IDENTIFIED);
        String identityBefore = sessions.read(SANDBOX, journeyId, Section.IDENTITY).orElseThrow();

        mvc.perform(MockMvcRequestBuilders.post(JOURNEY).contextPath(CONTEXT).with(host(SANDBOX_HOST))
                        .with(holder.token(tokens -> tokens.claim("given_name", "Otra")
                                .claim("family_name", "Persona").claim("personal_administrative_number", "99999999R"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("in_progress"))
                .andExpect(jsonPath("$.step").value("identified"));

        assertThat(journeyOf(holder)).isEqualTo(journeyId);
        assertThat(sessions.read(SANDBOX, journeyId, Section.IDENTITY)).contains(identityBefore);
        assertThat(sessionRowsOf(holder)).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-10 — postJourney_anotherSubject_opensItsOwnJourney")
    void postJourney_anotherSubject_opensItsOwnJourney() throws Exception {
        TestHolder first = newHolder();
        TestHolder second = newHolder();
        mvc.perform(open(first)).andExpect(status().isCreated());

        mvc.perform(open(second)).andExpect(status().isCreated());

        assertThat(journeyOf(second)).isNotEqualTo(journeyOf(first));
        mvc.perform(get(JOURNEY, second)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("AC-10 — databaseRows_afterOpening_holdTheReferenceAndNeverTheSubjectOrThePersonalData")
    void databaseRows_afterOpening_holdTheReferenceAndNeverTheSubjectOrThePersonalData() throws Exception {
        TestHolder holder = newHolder();
        mvc.perform(open(holder)).andExpect(status().isCreated());

        String journeys = jdbc.sql("SELECT string_agg(to_jsonb(j)::text, ' ') FROM \"sandbox_issuance_portal\".journey j")
                .query(String.class).single();
        String journeySessions = jdbc.sql(
                        "SELECT string_agg(to_jsonb(s)::text, ' ') FROM \"sandbox_issuance_portal\".journey_session s")
                .query(String.class).single();

        assertThat(journeySessions).contains(holder.ref().value());
        assertThat(journeys + journeySessions)
                .doesNotContain(holder.subject())
                .doesNotContain(AccessTokens.GIVEN_NAME)
                .doesNotContain(AccessTokens.FAMILY_NAME)
                .doesNotContain(AccessTokens.PERSONAL_NUMBER);
    }

    @Test
    @DisplayName("EC-03 — postJourney_severalAtTheSameTime_endsWithOneActiveJourneyAndEveryAnswerReturnsIt")
    void postJourney_severalAtTheSameTime_endsWithOneActiveJourneyAndEveryAnswerReturnsIt() throws Exception {
        TestHolder holder = newHolder();
        long journeysBefore = journeyCount();
        int requests = 8;
        CountDownLatch go = new CountDownLatch(1);
        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(requests)) {
            List<Future<Integer>> answers = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                answers.add(pool.submit(() -> {
                    go.await();
                    return mvc.perform(open(holder)).andReturn().getResponse().getStatus();
                }));
            }
            go.countDown();
            for (Future<Integer> answer : answers) {
                statuses.add(answer.get());
            }
        }

        assertThat(statuses).containsOnly(200, 201).filteredOn(code -> code == 201).hasSize(1);
        assertThat(sessionRowsOf(holder)).isEqualTo(1);
        assertThat(journeyCount()).isEqualTo(journeysBefore + 1);
        mvc.perform(get(JOURNEY, holder)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("EC-04 — journeyOpenedWithTheCookie_isNotReachedByAnyHolderAndExpiresOnItsOwn")
    void journeyOpenedWithTheCookie_isNotReachedByAnyHolderAndExpiresOnItsOwn() throws Exception {
        UUID legacy = UUID.randomUUID();
        jdbc.sql("INSERT INTO \"sandbox_issuance_portal\".journey (id, state, step, created_at, last_activity) "
                        + "VALUES (:id, 'in_progress', 'identified', now(), now() - interval '16 minutes')")
                .param("id", legacy).update();
        jdbc.sql("INSERT INTO \"sandbox_issuance_portal\".journey_session (journey_id) VALUES (:id)")
                .param("id", legacy).update();
        TestHolder holder = newHolder();

        mvc.perform(open(holder)).andExpect(status().isCreated());

        assertThat(journeyOf(holder)).isNotEqualTo(legacy);
        assertThat(journeyService.expireInactive(SANDBOX)).isGreaterThanOrEqualTo(1);
        assertThat(journeyRow(legacy)).containsEntry("state", "withdrawn").containsEntry("withdrawal_reason", "expired");
        assertThat(sessionRows(legacy)).isZero();
    }

    // --- ES-03: a token without the identity cannot open a journey ---

    @ParameterizedTest
    @ValueSource(strings = {"given_name", "family_name", "personal_administrative_number"})
    @DisplayName("ES-03 — postJourney_tokenWithoutAClaimOfTheIdentity_returns401CreatesNothingAndWarnsWithoutPersonalData")
    void postJourney_tokenWithoutAClaimOfTheIdentity_returns401CreatesNothingAndWarnsWithoutPersonalData(String claim)
            throws Exception {
        TestHolder holder = newHolder();
        long journeysBefore = journeyCount();

        mvc.perform(MockMvcRequestBuilders.post(JOURNEY).contextPath(CONTEXT).with(host(SANDBOX_HOST))
                        .with(holder.token(tokens -> tokens.without(claim))))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(jsonPath("$.type").value("unauthenticated"));
        mvc.perform(MockMvcRequestBuilders.post(JOURNEY).contextPath(CONTEXT).with(host(SANDBOX_HOST))
                        .with(holder.token(tokens -> tokens.claim(claim, " "))))
                .andExpect(status().isUnauthorized());

        assertThat(journeyCount()).isEqualTo(journeysBefore);
        assertThat(sessionRowsOf(holder)).isZero();
        assertThat(logs.list).filteredOn(event -> event.getLevel() == Level.WARN)
                .extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(message -> assertThat(message).contains("no journey opened"));
        assertNoPersonalDataInLogs(holder);
    }

    // --- ES-05: the other operations of the journey need the holder's active journey ---

    @ParameterizedTest
    @ValueSource(strings = {"GET /journey", "POST /journey/withdrawal", "POST /journey/confirmation",
            "POST /journey/email-verification", "POST /journey/email-verification/code", "POST /entitlement"})
    @DisplayName("ES-05 — journeyOperation_holderWithoutActiveJourney_returns410JourneyClosed")
    void journeyOperation_holderWithoutActiveJourney_returns410JourneyClosed(String operation) throws Exception {
        TestHolder holder = newHolder();
        String[] parts = operation.split(" ");
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(HttpMethod.valueOf(parts[0]), API + parts[1])
                .contextPath(CONTEXT).with(host(SANDBOX_HOST)).with(holder.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\",\"email\":\"ana@example.org\"}");

        mvc.perform(request)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.type").value("journey_closed"));
    }

    @Test
    @DisplayName("ES-05 — getJourney_journeyOfAnotherTenant_returns410")
    void getJourney_journeyOfAnotherTenant_returns410() throws Exception {
        String subject = "holder-" + UUID.randomUUID();
        TestHolder inKpmg = TestHolder.of(KPMG, subject, holderRefs);
        TestHolder inSandbox = TestHolder.of(SANDBOX, subject, holderRefs);
        mvc.perform(MockMvcRequestBuilders.post(JOURNEY).contextPath(CONTEXT).with(host(KPMG_HOST)).with(inKpmg.token()))
                .andExpect(status().isCreated());

        mvc.perform(get(JOURNEY, inSandbox)).andExpect(status().isGone());
        mvc.perform(MockMvcRequestBuilders.get(JOURNEY).contextPath(CONTEXT).with(host(KPMG_HOST)).with(inKpmg.token()))
                .andExpect(status().isOk());
        assertThat(inKpmg.ref()).isNotEqualTo(inSandbox.ref());
    }

    @Test
    @DisplayName("ES-05 — getAndWithdraw_withdrawnJourney_return410")
    void getAndWithdraw_withdrawnJourney_return410() throws Exception {
        TestHolder holder = start();
        mvc.perform(post(JOURNEY + "/withdrawal", holder)).andExpect(status().isOk());

        mvc.perform(get(JOURNEY, holder))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.type").value("journey_closed"));
        mvc.perform(post(JOURNEY + "/withdrawal", holder))
                .andExpect(status().isGone());
    }

    // --- AC-11: no cookie, no header filter ---

    @Test
    @DisplayName("AC-11 — anyResponse_carriesNoSetCookie")
    void anyResponse_carriesNoSetCookie() throws Exception {
        TestHolder holder = newHolder();
        List<MvcResult> results = new ArrayList<>();
        results.add(mvc.perform(open(holder)).andReturn());
        results.add(mvc.perform(open(holder)).andReturn());
        results.add(mvc.perform(get(JOURNEY, holder)).andReturn());
        results.add(mvc.perform(post(JOURNEY + "/withdrawal", holder)).andReturn());
        results.add(mvc.perform(get(JOURNEY, holder)).andReturn());
        results.add(mvc.perform(MockMvcRequestBuilders.get(JOURNEY).contextPath(CONTEXT).with(host(SANDBOX_HOST))
                .header(HttpHeaders.AUTHORIZATION, "Bearer garbage")).andReturn());

        assertThat(results).extracting(result -> result.getResponse().getStatus())
                .containsExactly(201, 200, 200, 200, 410, 401);
        assertThat(results).allSatisfy(result -> assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty());
    }

    @Test
    @DisplayName("AC-11 — postJourney_withoutRequestedWithHeader_isNotRejected")
    void postJourney_withoutRequestedWithHeader_isNotRejected() throws Exception {
        TestHolder holder = newHolder();

        mvc.perform(MockMvcRequestBuilders.post(JOURNEY).contextPath(CONTEXT).with(host(SANDBOX_HOST)).with(holder.token()))
                .andExpect(status().isCreated());
    }

    @Test
    void anyResponse_successOrError_carriesTheSecurityHeaders() throws Exception {
        MvcResult ok = mvc.perform(open(newHolder())).andExpect(status().isCreated()).andReturn();
        MvcResult unauthorized = mvc.perform(MockMvcRequestBuilders.post(JOURNEY).contextPath(CONTEXT)
                        .with(host(SANDBOX_HOST)).header(HttpHeaders.AUTHORIZATION, "Bearer garbage"))
                .andExpect(status().isUnauthorized()).andReturn();

        for (MvcResult result : List.of(ok, unauthorized)) {
            assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
            assertThat(result.getResponse().getHeader("Pragma")).isEqualTo("no-cache");
            assertThat(result.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(result.getResponse().getHeader("X-Frame-Options")).isEqualTo("DENY");
            assertThat(result.getResponse().getHeader("Content-Security-Policy"))
                    .isEqualTo("default-src 'none'; frame-ancestors 'none'");
            assertThat(result.getResponse().getHeader("Strict-Transport-Security")).startsWith("max-age=31536000");
            assertThat(result.getResponse().getHeader("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
            assertThat(result.getResponse().getHeader("Permissions-Policy")).contains("camera=()");
        }
    }

    // --- NFR-2: nothing of the holder in the logs ---

    @Test
    @DisplayName("NFR-2 — journeyCalls_openReturnWithdrawAndRejected_logNeitherSubjectReferenceNorIdentity")
    void journeyCalls_openReturnWithdrawAndRejected_logNeitherSubjectReferenceNorIdentity() throws Exception {
        TestHolder holder = newHolder();
        TestHolder withoutJourney = newHolder();

        mvc.perform(open(holder)).andExpect(status().isCreated());
        mvc.perform(open(holder)).andExpect(status().isOk());
        mvc.perform(get(JOURNEY, holder)).andExpect(status().isOk());
        mvc.perform(get(JOURNEY, withoutJourney)).andExpect(status().isGone());
        mvc.perform(MockMvcRequestBuilders.post(JOURNEY).contextPath(CONTEXT).with(host(SANDBOX_HOST))
                        .with(holder.token(tokens -> tokens.without("given_name"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(JOURNEY + "/withdrawal", holder)).andExpect(status().isOk());

        assertThat(logs.list).isNotEmpty();
        assertNoPersonalDataInLogs(holder, withoutJourney);
    }

    // --- Lifecycle of the journey ---

    @Test
    @DisplayName("AC-02 — getJourney_journeyInProgress_returnsStateAndStepAndRecordsTheActivity")
    void getJourney_journeyInProgress_returnsStateAndStepAndRecordsTheActivity() throws Exception {
        TestHolder holder = start();
        UUID journeyId = journeyOf(holder);
        jdbc.sql("UPDATE \"sandbox_issuance_portal\".journey SET last_activity = now() - interval '5 minutes' "
                + "WHERE id = :id").param("id", journeyId).update();
        Instant before = lastActivity(journeyId);

        mvc.perform(get(JOURNEY, holder))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("in_progress"))
                .andExpect(jsonPath("$.step").value("identified"));

        assertThat(lastActivity(journeyId)).isAfter(before);
    }

    @Test
    @DisplayName("AC-03 — postWithdrawal_atDataStep_withdrawsWithStepAndDeletesPersonalData")
    void postWithdrawal_atDataStep_withdrawsWithStepAndDeletesPersonalData() throws Exception {
        TestHolder holder = start();
        UUID journeyId = journeyOf(holder);
        sessions.write(SANDBOX, journeyId, Section.IDENTITY, "{\"givenName\":\"Ana\"}");
        progress.reach(SANDBOX, holder.ref(), JourneyProgress.Step.ENTITLEMENT_CONFIRMED);

        mvc.perform(post(JOURNEY + "/withdrawal", holder))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("withdrawn"))
                .andExpect(jsonPath("$.step").value("entitlement_confirmed"))
                .andExpect(jsonPath("$.withdrawal_reason").value("cancelled"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));

        assertThat(sessionRows(journeyId)).isZero();
        assertThat(journeyRow(journeyId)).containsEntry("state", "withdrawn").containsKey("ended_at");
        assertThat(journeyRow(journeyId).get("ended_at")).isNotNull();
    }

    @Test
    @DisplayName("AC-10 — postJourney_afterWithdrawing_opensANewJourneyForTheSameHolder")
    void postJourney_afterWithdrawing_opensANewJourneyForTheSameHolder() throws Exception {
        TestHolder holder = start();
        UUID first = journeyOf(holder);
        mvc.perform(post(JOURNEY + "/withdrawal", holder)).andExpect(status().isOk());

        mvc.perform(open(holder)).andExpect(status().isCreated());

        assertThat(journeyOf(holder)).isNotEqualTo(first);
        assertThat(journeyRow(first)).containsEntry("state", "withdrawn");
    }

    @Test
    @DisplayName("AC-06 — withdrawalPort_notEntitled_withdrawsKeepingStep")
    void withdrawalPort_notEntitled_withdrawsKeepingStep() throws Exception {
        TestHolder holder = start();
        UUID journeyId = journeyOf(holder);
        progress.reach(SANDBOX, holder.ref(), JourneyProgress.Step.IDENTIFIED);

        withdrawal.withdraw(SANDBOX, holder.ref(), JourneyWithdrawal.Cause.NOT_ENTITLED);

        assertThat(journeyRow(journeyId))
                .containsEntry("state", "withdrawn")
                .containsEntry("step", "identified")
                .containsEntry("withdrawal_reason", "not_entitled");
        assertThat(sessionRows(journeyId)).isZero();
    }

    @Test
    @DisplayName("AC-25 — postWithdrawal_completedJourney_endsItStaysCompletedAndDeletesTheSession")
    void postWithdrawal_completedJourney_endsItStaysCompletedAndDeletesTheSession() throws Exception {
        TestHolder holder = start();
        UUID journeyId = journeyOf(holder);
        jdbc.sql("UPDATE \"sandbox_issuance_portal\".journey SET state = 'completed', step = 'offer_obtained', "
                + "completed_at = now() WHERE id = :id").param("id", journeyId).update();

        mvc.perform(post(JOURNEY + "/withdrawal", holder))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("completed"))
                .andExpect(jsonPath("$.step").value("offer_obtained"));

        assertThat(journeyRow(journeyId)).containsEntry("state", "completed");
        assertThat(journeyRow(journeyId).get("ended_at")).isNotNull();
        assertThat(sessionRows(journeyId)).isZero();
        mvc.perform(get(JOURNEY, holder)).andExpect(status().isGone())
                .andExpect(jsonPath("$.type").value("journey_closed"));
    }

    @Test
    @DisplayName("AC-10 — postJourney_holderWithCompletedJourneyStillOpen_returnsItWithItsOffer")
    void postJourney_holderWithCompletedJourneyStillOpen_returnsItWithItsOffer() throws Exception {
        TestHolder holder = start();
        UUID journeyId = journeyOf(holder);
        jdbc.sql("UPDATE \"sandbox_issuance_portal\".journey SET state = 'completed', step = 'offer_obtained', "
                + "completed_at = now() WHERE id = :id").param("id", journeyId).update();
        sessions.write(SANDBOX, journeyId, Section.ISSUANCE, new IssuanceRecord(UUID.randomUUID(), null,
                "openid-credential-offer://?credential_offer_uri=https%3A%2F%2Fissuer%2Foffer%2F1",
                Instant.parse("2030-01-01T10:10:00Z"), "https://sandbox.127.0.0.1.nip.io:4443/t/Zk3q9Xw2",
                Instant.parse("2030-01-01T10:09:00Z")).toJson());

        mvc.perform(open(holder))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("completed"))
                .andExpect(jsonPath("$.offer.credential_offer_uri").exists());

        assertThat(journeyOf(holder)).isEqualTo(journeyId);
    }

    @Test
    @DisplayName("EC-03 — getJourney_staleJourneyBeforeExpiryJob_returns410AndWithdrawsAsExpired")
    void getJourney_staleJourneyBeforeExpiryJob_returns410AndWithdrawsAsExpired() throws Exception {
        TestHolder holder = start();
        UUID journeyId = journeyOf(holder);
        jdbc.sql("UPDATE \"sandbox_issuance_portal\".journey SET last_activity = now() - interval '16 minutes' "
                + "WHERE id = :id").param("id", journeyId).update();
        Instant before = lastActivity(journeyId);

        mvc.perform(get(JOURNEY, holder))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.type").value("journey_closed"));

        assertThat(journeyRow(journeyId))
                .containsEntry("state", "withdrawn")
                .containsEntry("withdrawal_reason", "expired");
        assertThat(sessionRows(journeyId)).isZero();
        assertThat(lastActivity(journeyId)).isEqualTo(before);
    }

    @Test
    @DisplayName("EC-03 — postWithdrawal_staleJourneyBeforeExpiryJob_returns410AndWithdrawsAsExpired")
    void postWithdrawal_staleJourneyBeforeExpiryJob_returns410AndWithdrawsAsExpired() throws Exception {
        TestHolder holder = start();
        UUID journeyId = journeyOf(holder);
        jdbc.sql("UPDATE \"sandbox_issuance_portal\".journey SET last_activity = now() - interval '16 minutes' "
                + "WHERE id = :id").param("id", journeyId).update();
        Instant before = lastActivity(journeyId);

        mvc.perform(post(JOURNEY + "/withdrawal", holder))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.type").value("journey_closed"));

        assertThat(journeyRow(journeyId))
                .containsEntry("state", "withdrawn")
                .containsEntry("withdrawal_reason", "expired");
        assertThat(sessionRows(journeyId)).isZero();
        assertThat(lastActivity(journeyId)).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-10 — postJourney_staleJourneyBeforeExpiryJob_expiresItAndOpensANewOne")
    void postJourney_staleJourneyBeforeExpiryJob_expiresItAndOpensANewOne() throws Exception {
        TestHolder holder = start();
        UUID stale = journeyOf(holder);
        jdbc.sql("UPDATE \"sandbox_issuance_portal\".journey SET last_activity = now() - interval '16 minutes' "
                + "WHERE id = :id").param("id", stale).update();

        mvc.perform(open(holder)).andExpect(status().isCreated());

        assertThat(journeyOf(holder)).isNotEqualTo(stale);
        assertThat(journeyRow(stale)).containsEntry("state", "withdrawn").containsEntry("withdrawal_reason", "expired");
    }

    @Test
    void postJourney_unknownTenant_returns404() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post(JOURNEY).contextPath(CONTEXT).with(host("altia.127.0.0.1.nip.io")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("tenant_not_found"));
    }

    @Test
    @DisplayName("AC-16, EUD-277 — getJourney_entitlementConfirmed_returnsAttestationPreview")
    void getJourney_entitlementConfirmed_returnsAttestationPreview() throws Exception {
        TestHolder holder = start();
        progress.reach(SANDBOX, holder.ref(), JourneyProgress.Step.IDENTIFIED);
        sessions.write(SANDBOX, journeyOf(holder), Section.ENTITLEMENT, entitlement());
        progress.reach(SANDBOX, holder.ref(), JourneyProgress.Step.ENTITLEMENT_CONFIRMED);

        mvc.perform(get(JOURNEY, holder))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.step").value("entitlement_confirmed"))
                .andExpect(jsonPath("$.attestation.type_name").value("DoctorID"))
                .andExpect(jsonPath("$.attestation.fields[0].label").value("Nombre y apellidos"))
                .andExpect(jsonPath("$.attestation.fields[0].value").value("Lucía Prueba Sintética"))
                .andExpect(jsonPath("$.attestation.fields[1].value").value("280000001"))
                .andExpect(jsonPath("$.attestation.email").value("lucia.prueba@example.org"))
                .andExpect(jsonPath("$.attestation.email_required").value(false))
                .andExpect(jsonPath("$.offer").doesNotExist());
    }

    @Test
    @DisplayName("AC-17, EUD-277 — getJourney_offerObtained_returnsOfferAsStored")
    void getJourney_offerObtained_returnsOfferAsStored() throws Exception {
        TestHolder holder = start();
        progress.reach(SANDBOX, holder.ref(), JourneyProgress.Step.IDENTIFIED);
        sessions.write(SANDBOX, journeyOf(holder), Section.ENTITLEMENT, entitlement());
        progress.reach(SANDBOX, holder.ref(), JourneyProgress.Step.ENTITLEMENT_CONFIRMED);
        Instant expires = Instant.parse("2030-01-01T10:10:00Z");
        Instant onboardingExpires = Instant.parse("2030-01-01T10:09:00Z");
        sessions.write(SANDBOX, journeyOf(holder), Section.ISSUANCE, new IssuanceRecord(
                UUID.randomUUID(), null, "openid-credential-offer://?credential_offer_uri=https%3A%2F%2Fissuer%2Foffer%2F1",
                expires, "https://sandbox.127.0.0.1.nip.io:4443/t/Zk3q9Xw2", onboardingExpires).toJson());

        mvc.perform(get(JOURNEY, holder))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attestation.type_name").value("DoctorID"))
                .andExpect(jsonPath("$.offer.credential_offer_uri")
                        .value("openid-credential-offer://?credential_offer_uri=https%3A%2F%2Fissuer%2Foffer%2F1"))
                .andExpect(jsonPath("$.offer.expires_at").value("2030-01-01T10:10:00Z"))
                .andExpect(jsonPath("$.offer.onboarding_url").value("https://sandbox.127.0.0.1.nip.io:4443/t/Zk3q9Xw2"))
                .andExpect(jsonPath("$.offer.onboarding_expires_at").value("2030-01-01T10:09:00Z"));
    }

    @Test
    @DisplayName("EC-12, EUD-277 — postJourneyAndWithdrawal_answerWithoutAttestationOrOffer")
    void postJourneyAndWithdrawal_answerWithoutAttestationOrOffer() throws Exception {
        TestHolder holder = start();
        progress.reach(SANDBOX, holder.ref(), JourneyProgress.Step.IDENTIFIED);
        sessions.write(SANDBOX, journeyOf(holder), Section.ENTITLEMENT, entitlement());
        progress.reach(SANDBOX, holder.ref(), JourneyProgress.Step.ENTITLEMENT_CONFIRMED);

        mvc.perform(post(JOURNEY + "/withdrawal", holder))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attestation").doesNotExist())
                .andExpect(jsonPath("$.offer").doesNotExist());
        mvc.perform(get(JOURNEY, holder))
                .andExpect(status().isGone());
    }

    private static String entitlement() {
        return EntitlementRecords.available(
                IdentifierType.COLEGIADO, Map.of("license_number", "280000001"),
                new EntitlementRecord.Preview("DoctorID", List.of(
                        new EntitlementRecord.Field("Nombre y apellidos", "Lucía Prueba Sintética"),
                        new EntitlementRecord.Field("Número de colegiado", "280000001"))),
                "lucia.prueba@example.org", Instant.parse("2026-11-09T10:00:00Z")).toJson();
    }

    // --- helpers ---

    private TestHolder newHolder() {
        return TestHolder.of(SANDBOX, holderRefs);
    }

    /** A holder with a journey in progress. */
    private TestHolder start() throws Exception {
        TestHolder holder = newHolder();
        mvc.perform(open(holder)).andExpect(status().isCreated());
        return holder;
    }

    private UUID journeyOf(TestHolder holder) {
        return sessions.journeyOf(SANDBOX, holder.ref()).orElseThrow();
    }

    private static MockHttpServletRequestBuilder open(TestHolder holder) {
        return post(JOURNEY, holder);
    }

    /** A request to the service as nginx forwards it, with the holder's Access Token. */
    private static MockHttpServletRequestBuilder post(String path, TestHolder holder) {
        return MockMvcRequestBuilders.post(path).contextPath(CONTEXT).with(host(SANDBOX_HOST)).with(holder.token());
    }

    private static MockHttpServletRequestBuilder get(String path, TestHolder holder) {
        return MockMvcRequestBuilders.get(path).contextPath(CONTEXT).with(host(SANDBOX_HOST)).with(holder.token());
    }

    private static RequestPostProcessor host(String host) {
        return request -> {
            request.setServerName(host);
            return request;
        };
    }

    private Map<String, Object> journeyRow(UUID id) {
        return jdbc.sql("SELECT state, step, withdrawal_reason, identification_method, ended_at "
                        + "FROM \"sandbox_issuance_portal\".journey WHERE id = :id")
                .param("id", id).query().singleRow();
    }

    private Instant lastActivity(UUID journeyId) {
        return jdbc.sql("SELECT last_activity FROM \"sandbox_issuance_portal\".journey WHERE id = :id")
                .param("id", journeyId).query(Timestamp.class).single().toInstant();
    }

    private long sessionRows(UUID journeyId) {
        return jdbc.sql("SELECT count(*) FROM \"sandbox_issuance_portal\".journey_session WHERE journey_id = :j")
                .param("j", journeyId).query(Long.class).single();
    }

    private long sessionRowsOf(TestHolder holder) {
        return jdbc.sql("SELECT count(*) FROM \"sandbox_issuance_portal\".journey_session WHERE holder_ref = :h")
                .param("h", holder.ref().value()).query(Long.class).single();
    }

    private long journeyCount() {
        return jdbc.sql("SELECT count(*) FROM \"sandbox_issuance_portal\".journey").query(Long.class).single();
    }

    /** NFR-2: neither the subject, nor the reference, nor the identity of the holders, in any line, MDC or exception. */
    private void assertNoPersonalDataInLogs(TestHolder... holders) {
        List<String> secrets = new ArrayList<>(List.of(AccessTokens.GIVEN_NAME, AccessTokens.FAMILY_NAME,
                AccessTokens.PERSONAL_NUMBER));
        for (TestHolder holder : holders) {
            secrets.add(holder.subject());
            secrets.add(holder.ref().value());
        }
        assertThat(logs.list).extracting(JourneyApiIT::describe).allSatisfy(line -> assertThat(line).doesNotContain(secrets.toArray(String[]::new)));
    }

    private static String describe(ILoggingEvent event) {
        return event.getFormattedMessage() + " " + event.getMDCPropertyMap() + describe(event.getThrowableProxy());
    }

    private static String describe(IThrowableProxy throwable) {
        return throwable == null ? "" : throwable.getClassName() + ":" + throwable.getMessage()
                + describe(throwable.getCause());
    }
}
