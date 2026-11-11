package es.in2.issuanceportal.journey.infrastructure.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import es.in2.issuanceportal.TestcontainersConfiguration;
import es.in2.issuanceportal.journey.RecordingEmailCodeMailer;
import es.in2.issuanceportal.journey.api.JourneyProgress;
import es.in2.issuanceportal.journey.domain.EmailVerification;
import es.in2.issuanceportal.session.api.EmailRecord;
import es.in2.issuanceportal.session.api.EmailVerificationRecord;
import es.in2.issuanceportal.session.api.EntitlementRecord;
import es.in2.issuanceportal.session.api.HolderIdentity;
import es.in2.issuanceportal.session.api.IdentifierType;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /journey/email-verification} end to end against PostgreSQL, with the mail provider in the hands of
 * the test. The limit per client address is shared by every test of the context, so each test uses an address
 * of its own.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, EmailVerificationApiIT.Mail.class})
class EmailVerificationApiIT {

    private static final String CONTEXT = "/issuance-portal";
    private static final String SANDBOX_HOST = "sandbox.127.0.0.1.nip.io";
    private static final TenantId SANDBOX = new TenantId("sandbox");
    private static final String EMAIL = "Elena.Ruiz@example.test";
    private static final String ENDPOINT = CONTEXT + "/api/v1/journey/email-verification";
    private static final String CONFIG = """
            {"texts": {"es": {"portal": {"app_name": "Portal DoctorID"}}, "en": {"portal": {"app_name": "DoctorID Portal"}}},
             "legal_links": {}, "support": {}, "wallet_url": "https://sandbox.127.0.0.1.nip.io:4443/wallet"}""";
    private static final AtomicInteger NEXT_CLIENT = new AtomicInteger(1);

    @TestConfiguration
    static class Mail {
        @Bean
        @Primary
        RecordingEmailCodeMailer recordingEmailCodeMailer() {
            return new RecordingEmailCodeMailer();
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
    private RecordingEmailCodeMailer mailer;
    @Autowired
    private Clock clock;

    private String client;
    private ListAppender<ILoggingEvent> logs;
    private Logger rootLogger;

    @BeforeEach
    void setUp() {
        mailer.reset();
        client = "198.51.100." + NEXT_CLIENT.getAndIncrement();
        jdbc.sql("INSERT INTO \"sandbox_issuance_portal\".portal_config (tenant, config) VALUES ('sandbox', CAST(:config AS jsonb)) "
                        + "ON CONFLICT (tenant) DO UPDATE SET config = EXCLUDED.config")
                .param("config", CONFIG).update();
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

    // --- AC-01, EC-01, EC-07 ---

    @Test
    @DisplayName("AC-01 — postEmailVerification_entitledHolder_answers202SendsTheCodeAndKeepsOnlyItsHash")
    void postEmailVerification_entitledHolder_answers202SendsTheCodeAndKeepsOnlyItsHash() throws Exception {
        SessionId session = entitledJourney();

        mvc.perform(send(session, "{\"email\":\"" + EMAIL + "\"}", null))
                .andExpect(status().isAccepted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.verified").value(false))
                .andExpect(jsonPath("$.code_expires_at").isNotEmpty())
                .andExpect(jsonPath("$.resend_available_at").isNotEmpty())
                .andExpect(jsonPath("$.sends_left").value(2));

        assertThat(mailer.sent()).hasSize(1);
        assertThat(mailer.last().to()).isEqualTo(EMAIL);
        String code = RecordingEmailCodeMailer.codeOf(mailer.last());
        EmailVerificationRecord stored = storedVerification(session);
        assertThat(stored.codeHash()).isEqualTo(EmailVerification.hash(journeyOf(session), code));
        assertThat(stored.pendingEmail()).isEqualTo(EMAIL);
        assertThat(stored.sends()).isEqualTo(1);
        assertThat(stored.attempts()).isZero();
        assertThat(Duration.between(stored.lastSentAt(), stored.codeExpiresAt())).isEqualTo(Duration.ofMinutes(10));
        assertThat(journeyRow(session)).containsEntry("state", "in_progress")
                .containsEntry("step", "entitlement_confirmed");
    }

    @Test
    @DisplayName("EC-07 — postEmailVerification_emailWithSpaces_sendsToTheStrippedAddressWithItsCapitals")
    void postEmailVerification_emailWithSpaces_sendsToTheStrippedAddressWithItsCapitals() throws Exception {
        SessionId session = entitledJourney();

        mvc.perform(send(session, "{\"email\":\"  Elena.Ruiz@Example.Test \"}", null))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.email").value("Elena.Ruiz@Example.Test"));

        assertThat(mailer.last().to()).isEqualTo("Elena.Ruiz@Example.Test");
        assertThat(storedVerification(session).pendingEmail()).isEqualTo("Elena.Ruiz@Example.Test");
    }

    @Test
    @DisplayName("AC-07 — postEmailVerification_journeyWithVerifiedEmail_clearsItUntilTheNewCodeIsChecked")
    void postEmailVerification_journeyWithVerifiedEmail_clearsItUntilTheNewCodeIsChecked() throws Exception {
        SessionId session = entitledJourney();
        sessions.write(SANDBOX, session, Section.EMAIL, new EmailRecord("old@example.test").toJson());

        mvc.perform(send(session, "{\"email\":\"" + EMAIL + "\"}", null)).andExpect(status().isAccepted());

        assertThat(sessions.read(SANDBOX, session, Section.EMAIL)).isEmpty();
        assertThat(sessions.read(SANDBOX, session, Section.EMAIL_VERIFICATION)).isPresent();
    }

    // --- ES-01 ---

    @Test
    @DisplayName("ES-01 — postEmailVerification_noEmail_is400EmailRequiredAndSendsNothing")
    void postEmailVerification_noEmail_is400EmailRequiredAndSendsNothing() throws Exception {
        SessionId session = entitledJourney();

        for (String body : new String[] {null, "{}", "{\"email\":null}", "{\"email\":\"   \"}"}) {
            mvc.perform(send(session, body, null))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("email_required"));
        }

        assertNothingSent(session);
    }

    @Test
    @DisplayName("ES-01 — postEmailVerification_malformedOrTooLongEmail_is400EmailInvalidAndCountsForNoLimit")
    void postEmailVerification_malformedOrTooLongEmail_is400EmailInvalidAndCountsForNoLimit() throws Exception {
        SessionId session = entitledJourney();

        for (String email : new String[] {"\"not-an-email\"", "123", "\"" + "a".repeat(250) + "@example.test\""}) {
            mvc.perform(send(session, "{\"email\":" + email + "}", null))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("email_invalid"));
        }

        assertNothingSent(session);
        // Nothing counted: a valid send from the same address and journey still goes out at once.
        mvc.perform(send(session, "{\"email\":\"" + EMAIL + "\"}", null)).andExpect(status().isAccepted());
    }

    // --- ES-05, EC-04, ES-04 ---

    @Test
    @DisplayName("ES-05 — postEmailVerification_noEntitlement_is409StepNotAllowed")
    void postEmailVerification_noEntitlement_is409StepNotAllowed() throws Exception {
        SessionId session = identifiedJourney();

        mvc.perform(send(session, "{\"email\":\"" + EMAIL + "\"}", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("step_not_allowed"));

        assertNothingSent(session);
    }

    @Test
    @DisplayName("EC-04 — postEmailVerification_offerAlreadyDelivered_is409StepNotAllowed")
    void postEmailVerification_offerAlreadyDelivered_is409StepNotAllowed() throws Exception {
        SessionId session = entitledJourney();
        jdbc.sql("UPDATE \"sandbox_issuance_portal\".journey SET state = 'completed', step = 'offer_obtained', "
                        + "ended_at = now() WHERE id = :id")
                .param("id", journeyOf(session)).update();

        mvc.perform(send(session, "{\"email\":\"" + EMAIL + "\"}", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("step_not_allowed"));

        assertNothingSent(session);
    }

    @Test
    @DisplayName("ES-04 — postEmailVerification_withdrawnJourney_is410JourneyClosed")
    void postEmailVerification_withdrawnJourney_is410JourneyClosed() throws Exception {
        SessionId session = entitledJourney();
        mvc.perform(MockMvcRequestBuilders.post(CONTEXT + "/api/v1/journey/withdrawal").contextPath(CONTEXT)
                        .header("X-Requested-With", "issuance-portal").with(host())
                        .cookie(new Cookie("journey_session", session.value())))
                .andExpect(status().isOk());

        mvc.perform(send(session, "{\"email\":\"" + EMAIL + "\"}", null))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.type").value("journey_closed"));

        assertThat(mailer.sent()).isEmpty();
    }

    @Test
    @DisplayName("ES-04 — postEmailVerification_noSession_is410JourneyClosed")
    void postEmailVerification_noSession_is410JourneyClosed() throws Exception {
        mvc.perform(post(null).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + EMAIL + "\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.type").value("journey_closed"));

        assertThat(mailer.sent()).isEmpty();
    }

    // --- AC-04 ---

    @Test
    @DisplayName("AC-04 — postEmailVerification_resendBeforeThreeMinutes_is429ResendTooSoonWithRetryAfter")
    void postEmailVerification_resendBeforeThreeMinutes_is429ResendTooSoonWithRetryAfter() throws Exception {
        SessionId session = entitledJourney();
        mvc.perform(send(session, "{\"email\":\"" + EMAIL + "\"}", null)).andExpect(status().isAccepted());
        String firstSection = sessions.read(SANDBOX, session, Section.EMAIL_VERIFICATION).orElseThrow();

        MvcResult result = mvc.perform(send(session, "{\"email\":\"" + EMAIL + "\"}", null))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("resend_too_soon"))
                .andReturn();

        long retryAfter = Long.parseLong(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER));
        assertThat(retryAfter).isBetween(1L, 180L);
        assertThat(mailer.sent()).hasSize(1);
        assertThat(sessions.read(SANDBOX, session, Section.EMAIL_VERIFICATION)).contains(firstSection);
    }

    @Test
    @DisplayName("AC-04 — postEmailVerification_resendAfterThreeMinutes_sendsANewCodeAndReplacesThePrevious")
    void postEmailVerification_resendAfterThreeMinutes_sendsANewCodeAndReplacesThePrevious() throws Exception {
        SessionId session = entitledJourney();
        mvc.perform(send(session, "{\"email\":\"" + EMAIL + "\"}", null)).andExpect(status().isAccepted());
        EmailVerificationRecord first = storedVerification(session);
        backdate(session, first, Duration.ofMinutes(4));

        mvc.perform(send(session, "{\"email\":\"other@example.test\"}", null))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.email").value("other@example.test"))
                .andExpect(jsonPath("$.sends_left").value(1));

        assertThat(mailer.sent()).hasSize(2);
        EmailVerificationRecord second = storedVerification(session);
        assertThat(second.codeHash()).isNotEqualTo(first.codeHash())
                .isEqualTo(EmailVerification.hash(journeyOf(session), RecordingEmailCodeMailer.codeOf(mailer.last())));
        assertThat(second.sends()).isEqualTo(2);
        assertThat(second.pendingEmail()).isEqualTo("other@example.test");
    }

    // --- AC-05 ---

    @Test
    @DisplayName("AC-05 — postEmailVerification_afterThreeCodes_is429TooManyCodesWithoutRetryAfter")
    void postEmailVerification_afterThreeCodes_is429TooManyCodesWithoutRetryAfter() throws Exception {
        SessionId session = entitledJourney();
        mvc.perform(send(session, "{\"email\":\"" + EMAIL + "\"}", null)).andExpect(status().isAccepted());
        EmailVerificationRecord first = storedVerification(session);
        sessions.write(SANDBOX, session, Section.EMAIL_VERIFICATION, new EmailVerificationRecord(first.pendingEmail(),
                first.codeHash(), first.codeExpiresAt().minus(Duration.ofHours(1)), 0, 3,
                first.lastSentAt().minus(Duration.ofHours(1))).toJson());

        mvc.perform(send(session, "{\"email\":\"" + EMAIL + "\"}", null))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type").value("too_many_codes"))
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER));

        assertThat(mailer.sent()).hasSize(1);
    }

    @Test
    @DisplayName("AC-05 — postEmailVerification_tenSendsFromTheSameAddress_theEleventhIs429TooManyRequests")
    void postEmailVerification_tenSendsFromTheSameAddress_theEleventhIs429TooManyRequests() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(send(entitledJourney(), "{\"email\":\"" + EMAIL + "\"}", null)).andExpect(status().isAccepted());
        }
        SessionId eleventh = entitledJourney();

        MvcResult result = mvc.perform(send(eleventh, "{\"email\":\"" + EMAIL + "\"}", null))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type").value("too_many_requests"))
                .andReturn();

        assertThat(Long.parseLong(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER))).isBetween(1L, 3600L);
        assertThat(mailer.sent()).hasSize(10);
        assertThat(sessions.read(SANDBOX, eleventh, Section.EMAIL_VERIFICATION)).isEmpty();
        // Another address is not affected.
        client = "198.51.100.250";
        mvc.perform(send(entitledJourney(), "{\"email\":\"" + EMAIL + "\"}", null)).andExpect(status().isAccepted());
    }

    // --- AC-06 ---

    @Test
    @DisplayName("AC-06 — postEmailVerification_mailProviderFails_is503EmailUnavailableAndNothingIsKept")
    void postEmailVerification_mailProviderFails_is503EmailUnavailableAndNothingIsKept() throws Exception {
        SessionId session = entitledJourney();
        sessions.write(SANDBOX, session, Section.EMAIL, new EmailRecord("old@example.test").toJson());
        mailer.failFromNowOn();

        mvc.perform(send(session, "{\"email\":\"" + EMAIL + "\"}", null))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("email_unavailable"));

        assertThat(sessions.read(SANDBOX, session, Section.EMAIL_VERIFICATION)).isEmpty();
        assertThat(sessions.read(SANDBOX, session, Section.EMAIL)).isPresent();
    }

    @Test
    @DisplayName("AC-06 — postEmailVerification_failedSend_countsForNoLimit")
    void postEmailVerification_failedSend_countsForNoLimit() throws Exception {
        SessionId session = entitledJourney();
        mailer.failFromNowOn();
        mvc.perform(send(session, "{\"email\":\"" + EMAIL + "\"}", null)).andExpect(status().isServiceUnavailable());
        mailer.reset();

        // The journey's interval and count are untouched.
        mvc.perform(send(session, "{\"email\":\"" + EMAIL + "\"}", null))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.sends_left").value(2));
        // The address's budget is untouched too: nine more succeed after one failure, the tenth send of the
        // address is the last one allowed.
        for (int i = 0; i < 8; i++) {
            mvc.perform(send(entitledJourney(), "{\"email\":\"" + EMAIL + "\"}", null)).andExpect(status().isAccepted());
        }
        mailer.failFromNowOn();
        mvc.perform(send(entitledJourney(), "{\"email\":\"" + EMAIL + "\"}", null)).andExpect(status().isServiceUnavailable());
        mailer.reset();
        mvc.perform(send(entitledJourney(), "{\"email\":\"" + EMAIL + "\"}", null)).andExpect(status().isAccepted());
        mvc.perform(send(entitledJourney(), "{\"email\":\"" + EMAIL + "\"}", null)).andExpect(status().isTooManyRequests());
    }

    // --- EC-06 ---

    @Test
    @DisplayName("EC-06 — postEmailVerification_acceptLanguageEnglish_sendsTheEnglishTemplateWithTheTenantsName")
    void postEmailVerification_acceptLanguageEnglish_sendsTheEnglishTemplateWithTheTenantsName() throws Exception {
        SessionId session = entitledJourney();

        mvc.perform(send(session, "{\"email\":\"" + EMAIL + "\"}", "en-GB,en;q=0.9,es;q=0.5"))
                .andExpect(status().isAccepted());

        assertThat(mailer.last().subject()).isEqualTo("Your verification code");
        assertThat(mailer.last().body()).contains("DoctorID Portal", "10 minutes").doesNotContain("http");
    }

    @Test
    @DisplayName("EC-06 — postEmailVerification_noOrUnknownAcceptLanguage_sendsTheSpanishTemplate")
    void postEmailVerification_noOrUnknownAcceptLanguage_sendsTheSpanishTemplate() throws Exception {
        mvc.perform(send(entitledJourney(), "{\"email\":\"" + EMAIL + "\"}", null)).andExpect(status().isAccepted());
        mvc.perform(send(entitledJourney(), "{\"email\":\"" + EMAIL + "\"}", "fr-FR,de;q=0.5"))
                .andExpect(status().isAccepted());

        assertThat(mailer.sent()).hasSize(2).allSatisfy(message -> {
            assertThat(message.subject()).isEqualTo("Tu código de verificación");
            assertThat(message.body()).contains("Portal DoctorID", "10 minutos");
        });
    }

    @Test
    @DisplayName("EC-06 — postEmailVerification_noAppNameInTheConfiguration_usesTheDefaultName")
    void postEmailVerification_noAppNameInTheConfiguration_usesTheDefaultName() throws Exception {
        jdbc.sql("UPDATE \"sandbox_issuance_portal\".portal_config SET config = jsonb_set(config, '{texts}', '{}')")
                .update();

        mvc.perform(send(entitledJourney(), "{\"email\":\"" + EMAIL + "\"}", "en")).andExpect(status().isAccepted());

        assertThat(mailer.last().body()).contains("Issuance portal");
    }

    // --- AC-10 ---

    @Test
    @DisplayName("AC-10 — postEmailVerification_anyOutcome_logsAndStoreCarryNoEmailCodeOrHash")
    void postEmailVerification_anyOutcome_logsAndStoreCarryNoEmailCodeOrHash() throws Exception {
        SessionId session = entitledJourney();
        mvc.perform(send(session, "{\"email\":\"" + EMAIL + "\"}", null)).andExpect(status().isAccepted());
        mvc.perform(send(session, "{\"email\":\"" + EMAIL + "\"}", null)).andExpect(status().isTooManyRequests());
        mailer.failFromNowOn();
        backdate(session, storedVerification(session), Duration.ofMinutes(4));
        mvc.perform(send(session, "{\"email\":\"" + EMAIL + "\"}", null)).andExpect(status().isServiceUnavailable());

        String code = RecordingEmailCodeMailer.codeOf(mailer.last());
        String hash = storedVerification(session).codeHash();
        String everything = logs.list.stream().map(EmailVerificationApiIT::describe).collect(Collectors.joining("\n"));
        assertThat(everything).contains("EMAIL_CODE_REQUESTED", "result=sent", "result=resend_too_soon",
                "result=email_unavailable")
                .doesNotContain(EMAIL, EMAIL.toLowerCase(java.util.Locale.ROOT), "Elena", code, hash);
    }

    // --- helpers ---

    private void backdate(SessionId session, EmailVerificationRecord record, Duration by) {
        sessions.write(SANDBOX, session, Section.EMAIL_VERIFICATION, new EmailVerificationRecord(record.pendingEmail(),
                record.codeHash(), record.codeExpiresAt().minus(by), record.attempts(), record.sends(),
                record.lastSentAt().minus(by)).toJson());
    }

    private void assertNothingSent(SessionId session) {
        assertThat(mailer.sent()).isEmpty();
        assertThat(sessions.read(SANDBOX, session, Section.EMAIL_VERIFICATION)).isEmpty();
    }

    private EmailVerificationRecord storedVerification(SessionId session) {
        return EmailVerificationRecord.fromJson(
                sessions.read(SANDBOX, session, Section.EMAIL_VERIFICATION).orElseThrow());
    }

    private UUID journeyOf(SessionId session) {
        return sessions.journeyOf(SANDBOX, session).orElseThrow();
    }

    private Map<String, Object> journeyRow(SessionId session) {
        return jdbc.sql("SELECT state, step FROM \"sandbox_issuance_portal\".journey WHERE id = :id")
                .param("id", journeyOf(session)).query().singleRow();
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

    private SessionId identifiedJourney() throws Exception {
        SessionId session = startedJourney();
        sessions.write(SANDBOX, session, Section.IDENTITY, new HolderIdentity("Elena", "Ruiz Prieto",
                new PersonalIdentifier(IdentifierType.DNI, "00000001R"), "elena.token@example.test").toJson());
        progress.reach(SANDBOX, session, JourneyProgress.Step.IDENTIFIED);
        return session;
    }

    /** What Identification and Authentic source leave in the journey before the holder reaches Data. */
    private SessionId entitledJourney() throws Exception {
        SessionId session = identifiedJourney();
        sessions.write(SANDBOX, session, Section.ENTITLEMENT, new EntitlementRecord(IdentifierType.DNI,
                Map.of("legally_entitled", true),
                new EntitlementRecord.Preview("DoctorID", List.of(), null, true), clock.instant()).toJson());
        progress.reach(SANDBOX, session, JourneyProgress.Step.ENTITLEMENT_CONFIRMED);
        return session;
    }

    private MockHttpServletRequestBuilder send(SessionId session, String body, String acceptLanguage) {
        MockHttpServletRequestBuilder request = post(session);
        if (acceptLanguage != null) {
            request.header(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage);
        }
        return body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private MockHttpServletRequestBuilder post(SessionId session) {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.post(ENDPOINT)
                .contextPath(CONTEXT)
                .header("X-Requested-With", "issuance-portal")
                .with(host())
                .with(servletRequest -> {
                    servletRequest.setRemoteAddr(client);
                    return servletRequest;
                });
        return session == null ? request : request.cookie(new Cookie("journey_session", session.value()));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor host() {
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
