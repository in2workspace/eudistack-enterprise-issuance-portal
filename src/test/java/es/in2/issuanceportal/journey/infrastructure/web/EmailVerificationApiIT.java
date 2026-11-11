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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

        assertThat(mailer.last().subject()).isEqualTo("Your verification code — DoctorID Portal");
        assertThat(mailer.last().body()).contains("DoctorID Portal", "10 minutes").doesNotContain("http");
    }

    @Test
    @DisplayName("EC-06 — postEmailVerification_noOrUnknownAcceptLanguage_sendsTheSpanishTemplate")
    void postEmailVerification_noOrUnknownAcceptLanguage_sendsTheSpanishTemplate() throws Exception {
        mvc.perform(send(entitledJourney(), "{\"email\":\"" + EMAIL + "\"}", null)).andExpect(status().isAccepted());
        mvc.perform(send(entitledJourney(), "{\"email\":\"" + EMAIL + "\"}", "fr-FR,de;q=0.5"))
                .andExpect(status().isAccepted());

        assertThat(mailer.sent()).hasSize(2).allSatisfy(message -> {
            assertThat(message.subject()).isEqualTo("Tu código de verificación — Portal DoctorID");
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

    // --- the check: AC-02, AC-03, AC-04, AC-07, ES-02, ES-03, ES-04, EC-03 ---

    @Test
    @DisplayName("AC-02 — postCode_rightCode_answersTheJourneyWithTheVerifiedEmailAndTheCodeNoLongerServes")
    void postCode_rightCode_answersTheJourneyWithTheVerifiedEmailAndTheCodeNoLongerServes() throws Exception {
        SessionId session = entitledJourney();
        String code = sentCode(session);

        mvc.perform(check(session, code(code)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.state").value("in_progress"))
                .andExpect(jsonPath("$.step").value("email_verified"))
                .andExpect(jsonPath("$.email_verification.email").value(EMAIL))
                .andExpect(jsonPath("$.email_verification.verified").value(true))
                .andExpect(jsonPath("$.email_verification.code_expires_at").doesNotExist())
                .andExpect(jsonPath("$.email_verification.resend_available_at").isNotEmpty())
                .andExpect(jsonPath("$.email_verification.sends_left").value(2));

        assertThat(EmailRecord.fromJson(sessions.read(SANDBOX, session, Section.EMAIL).orElseThrow()).address())
                .isEqualTo(EMAIL);
        assertThat(storedVerification(session).codeHash()).isNull();
        assertThat(journeyRow(session)).containsEntry("step", "email_verified");
        mvc.perform(check(session, code(code)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("step_not_allowed"));
        mvc.perform(get(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.step").value("email_verified"))
                .andExpect(jsonPath("$.email_verification.verified").value(true));
    }

    @Test
    @DisplayName("AC-03 — postCode_wrongCode_is400InvalidCodeAndTheAttemptIsKept")
    void postCode_wrongCode_is400InvalidCodeAndTheAttemptIsKept() throws Exception {
        SessionId session = entitledJourney();
        String code = sentCode(session);

        mvc.perform(check(session, code(wrongCodeFor(code))))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("invalid_code"));

        // The answer is an error, and the attempt it spent is committed all the same.
        assertThat(storedVerification(session).attempts()).isEqualTo(1);
        assertThat(sessions.read(SANDBOX, session, Section.EMAIL)).isEmpty();
        assertThat(journeyRow(session)).containsEntry("step", "entitlement_confirmed");
    }

    @Test
    @DisplayName("AC-03 — postCode_fiveWrongCodes_theNextIs400TooManyAttemptsEvenWithTheRightOne")
    void postCode_fiveWrongCodes_theNextIs400TooManyAttemptsEvenWithTheRightOne() throws Exception {
        SessionId session = entitledJourney();
        String code = sentCode(session);
        for (int i = 0; i < 5; i++) {
            mvc.perform(check(session, code(wrongCodeFor(code)))).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("invalid_code"));
        }

        mvc.perform(check(session, code(code)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("too_many_attempts"));

        assertThat(storedVerification(session).attempts()).isEqualTo(5);
        assertThat(sessions.read(SANDBOX, session, Section.EMAIL)).isEmpty();
    }

    @Test
    @DisplayName("AC-03 — postCode_tenMinutesAfterTheSend_is400ExpiredCodeEvenWithTheRightOne")
    void postCode_tenMinutesAfterTheSend_is400ExpiredCodeEvenWithTheRightOne() throws Exception {
        SessionId session = entitledJourney();
        String code = sentCode(session);
        backdate(session, storedVerification(session), Duration.ofMinutes(10).plusSeconds(1));

        mvc.perform(check(session, code(code)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("expired_code"));

        assertThat(sessions.read(SANDBOX, session, Section.EMAIL)).isEmpty();
    }

    @Test
    @DisplayName("AC-04 — postCode_codeReplacedByAResend_is400ExpiredCodeAndTheNewOneVerifies")
    void postCode_codeReplacedByAResend_is400ExpiredCodeAndTheNewOneVerifies() throws Exception {
        SessionId session = entitledJourney();
        String first = sentCode(session);
        backdate(session, storedVerification(session), Duration.ofMinutes(4));
        String second = sentCode(session);

        if (!first.equals(second)) {
            mvc.perform(check(session, code(first)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("expired_code"));
        }
        mvc.perform(check(session, code(second))).andExpect(status().isOk())
                .andExpect(jsonPath("$.email_verification.verified").value(true))
                .andExpect(jsonPath("$.email_verification.sends_left").value(1));
    }

    @Test
    @DisplayName("AC-07 — postCode_newCodeToAnotherEmail_theOldEmailStopsBeingVerifiedAndTheNewOneIs")
    void postCode_newCodeToAnotherEmail_theOldEmailStopsBeingVerifiedAndTheNewOneIs() throws Exception {
        SessionId session = entitledJourney();
        mvc.perform(check(session, code(sentCode(session)))).andExpect(status().isOk());
        backdate(session, storedVerification(session), Duration.ofMinutes(4));

        mvc.perform(send(session, "{\"email\":\"new.address@example.test\"}", null)).andExpect(status().isAccepted());

        assertThat(sessions.read(SANDBOX, session, Section.EMAIL)).isEmpty();
        mvc.perform(get(session))
                .andExpect(jsonPath("$.step").value("email_verified"))
                .andExpect(jsonPath("$.email_verification.verified").value(false))
                .andExpect(jsonPath("$.email_verification.email").value("new.address@example.test"));
        mvc.perform(check(session, code(RecordingEmailCodeMailer.codeOf(mailer.last())))).andExpect(status().isOk());
        assertThat(EmailRecord.fromJson(sessions.read(SANDBOX, session, Section.EMAIL).orElseThrow()).address())
                .isEqualTo("new.address@example.test");
    }

    @Test
    @DisplayName("ES-02 — postCode_malformedCode_is400BadRequestAndSpendsNoAttempt")
    void postCode_malformedCode_is400BadRequestAndSpendsNoAttempt() throws Exception {
        SessionId session = entitledJourney();
        sentCode(session);

        for (String body : new String[] {null, "{}", "{\"code\":null}", "{\"code\":\"12345\"}",
                "{\"code\":\"1234567\"}", "{\"code\":\"12345a\"}", "{\"code\":123456}", "not json"}) {
            mvc.perform(check(session, body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("bad_request"));
        }

        assertThat(storedVerification(session).attempts()).isZero();
    }

    @Test
    @DisplayName("ES-03 — postCode_neverSentOrAlreadyVerified_is409StepNotAllowed")
    void postCode_neverSentOrAlreadyVerified_is409StepNotAllowed() throws Exception {
        SessionId session = entitledJourney();

        mvc.perform(check(session, code("123456")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("step_not_allowed"));
    }

    @Test
    @DisplayName("ES-04 — postCode_withdrawnJourneyOrNoSession_is410JourneyClosed")
    void postCode_withdrawnJourneyOrNoSession_is410JourneyClosed() throws Exception {
        SessionId session = entitledJourney();
        String code = sentCode(session);
        mvc.perform(MockMvcRequestBuilders.post(CONTEXT + "/api/v1/journey/withdrawal").contextPath(CONTEXT)
                        .header("X-Requested-With", "issuance-portal").with(host())
                        .cookie(new Cookie("journey_session", session.value())))
                .andExpect(status().isOk());

        mvc.perform(check(session, code(code))).andExpect(status().isGone())
                .andExpect(jsonPath("$.type").value("journey_closed"));
        mvc.perform(check(null, code(code))).andExpect(status().isGone())
                .andExpect(jsonPath("$.type").value("journey_closed"));
    }

    @Test
    @DisplayName("EC-03 — postCode_twoWrongCodesAtOnceWithFourFailures_oneIsInvalidTheOtherTooManyAndTheCountStopsAtFive")
    void postCode_twoWrongCodesAtOnceWithFourFailures_oneIsInvalidTheOtherTooManyAndTheCountStopsAtFive()
            throws Exception {
        SessionId session = entitledJourney();
        String code = sentCode(session);
        EmailVerificationRecord sent = storedVerification(session);
        sessions.write(SANDBOX, session, Section.EMAIL_VERIFICATION, new EmailVerificationRecord(sent.pendingEmail(),
                sent.codeHash(), sent.codeExpiresAt(), 4, sent.sends(), sent.lastSentAt()).toJson());
        CyclicBarrier together = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> answers = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                answers.add(pool.submit(() -> {
                    together.await(10, TimeUnit.SECONDS);
                    MvcResult result = mvc.perform(check(session, code(wrongCodeFor(code)))).andReturn();
                    assertThat(result.getResponse().getStatus()).isEqualTo(400);
                    return new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(result.getResponse().getContentAsString()).get("type").asText();
                }));
            }
            List<String> types = new ArrayList<>();
            for (Future<String> answer : answers) {
                types.add(answer.get(30, TimeUnit.SECONDS));
            }

            assertThat(types).containsExactlyInAnyOrder("invalid_code", "too_many_attempts");
        } finally {
            pool.shutdownNow();
        }
        assertThat(storedVerification(session).attempts()).isEqualTo(5);
    }

    // --- GET /journey: email_verification ---

    @Test
    @DisplayName("AC-09 — getJourney_codePending_showsTheEmailTheExpiryAndTheResendTime")
    void getJourney_codePending_showsTheEmailTheExpiryAndTheResendTime() throws Exception {
        SessionId session = entitledJourney();
        mvc.perform(get(session)).andExpect(jsonPath("$.email_verification").doesNotExist());
        sentCode(session);

        mvc.perform(get(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.step").value("entitlement_confirmed"))
                .andExpect(jsonPath("$.email_verification.email").value(EMAIL))
                .andExpect(jsonPath("$.email_verification.verified").value(false))
                .andExpect(jsonPath("$.email_verification.code_expires_at").isNotEmpty())
                .andExpect(jsonPath("$.email_verification.resend_available_at").isNotEmpty())
                .andExpect(jsonPath("$.email_verification.sends_left").value(2));
    }

    @Test
    @DisplayName("AC-09 — getJourney_offerObtained_hasNoEmailVerification")
    void getJourney_offerObtained_hasNoEmailVerification() throws Exception {
        SessionId session = entitledJourney();
        sentCode(session);
        jdbc.sql("UPDATE \"sandbox_issuance_portal\".journey SET state = 'completed', step = 'offer_obtained', "
                        + "ended_at = now() WHERE id = :id")
                .param("id", journeyOf(session)).update();

        mvc.perform(get(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("completed"))
                .andExpect(jsonPath("$.email_verification").doesNotExist());
    }

    @Test
    @DisplayName("AC-10 — postCode_anyOutcome_logsCarryTheResultButNoEmailCodeOrHash")
    void postCode_anyOutcome_logsCarryTheResultButNoEmailCodeOrHash() throws Exception {
        SessionId session = entitledJourney();
        String code = sentCode(session);
        String hash = storedVerification(session).codeHash();
        mvc.perform(check(session, code(wrongCodeFor(code)))).andExpect(status().isBadRequest());
        mvc.perform(check(session, code(code))).andExpect(status().isOk());
        mvc.perform(check(session, code(code))).andExpect(status().isConflict());

        String everything = logs.list.stream().map(EmailVerificationApiIT::describe).collect(Collectors.joining("\n"));
        assertThat(everything).contains("EMAIL_CODE_CHECKED", "result=invalid_code", "result=verified",
                "STEP_REACHED", "step=email_verified")
                .doesNotContain(EMAIL, EMAIL.toLowerCase(java.util.Locale.ROOT), "Elena", code, hash);
    }

    // --- helpers ---

    private MockHttpServletRequestBuilder get(SessionId session) {
        return MockMvcRequestBuilders.get(CONTEXT + "/api/v1/journey").contextPath(CONTEXT).with(host())
                .cookie(new Cookie("journey_session", session.value()));
    }

    private void backdate(SessionId session, EmailVerificationRecord record, Duration by) {
        sessions.write(SANDBOX, session, Section.EMAIL_VERIFICATION, new EmailVerificationRecord(record.pendingEmail(),
                record.codeHash(), record.codeExpiresAt() == null ? null : record.codeExpiresAt().minus(by),
                record.attempts(), record.sends(), record.lastSentAt().minus(by), record.voidedCodeHashes()).toJson());
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

    private MockHttpServletRequestBuilder check(SessionId session, String body) {
        MockHttpServletRequestBuilder request = postTo(ENDPOINT + "/code", session);
        return body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String code(String code) {
        return "{\"code\":\"" + code + "\"}";
    }

    /** Sends a code to the holder and returns it, as the mail provider got it. */
    private String sentCode(SessionId session) throws Exception {
        mvc.perform(send(session, "{\"email\":\"" + EMAIL + "\"}", null)).andExpect(status().isAccepted());
        return RecordingEmailCodeMailer.codeOf(mailer.last());
    }

    private static String wrongCodeFor(String code) {
        return code.equals("000000") ? "000001" : "000000";
    }

    private MockHttpServletRequestBuilder post(SessionId session) {
        return postTo(ENDPOINT, session);
    }

    private MockHttpServletRequestBuilder postTo(String path, SessionId session) {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.post(path)
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
