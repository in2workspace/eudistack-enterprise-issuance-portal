package es.in2.issuanceportal.journey.application;

import es.in2.issuanceportal.MutableClock;
import es.in2.issuanceportal.journey.InMemoryJourneyRepository;
import es.in2.issuanceportal.journey.RecordingEmailCodeMailer;
import es.in2.issuanceportal.journey.RecordingJourneyAuditTrail;
import es.in2.issuanceportal.journey.api.JourneyProgress;
import es.in2.issuanceportal.journey.application.port.EmailCodeRateLimit;
import es.in2.issuanceportal.journey.application.port.JourneyAuditTrail;
import es.in2.issuanceportal.journey.application.port.JourneyAuditTrail.EmailCodeOutcome;
import es.in2.issuanceportal.journey.domain.EmailLanguage;
import es.in2.issuanceportal.journey.domain.EmailVerification;
import es.in2.issuanceportal.journey.domain.Journey;
import es.in2.issuanceportal.journey.domain.JourneyClosedException;
import es.in2.issuanceportal.journey.domain.JourneyState;
import es.in2.issuanceportal.journey.domain.JourneyStep;
import es.in2.issuanceportal.journey.domain.config.LegalLinks;
import es.in2.issuanceportal.journey.domain.config.PortalConfig;
import es.in2.issuanceportal.journey.domain.config.Support;
import es.in2.issuanceportal.session.InMemoryJourneySessions;
import es.in2.issuanceportal.session.api.EmailRecord;
import es.in2.issuanceportal.session.api.EmailVerificationRecord;
import es.in2.issuanceportal.session.api.EntitlementRecord;
import es.in2.issuanceportal.session.api.HolderIdentity;
import es.in2.issuanceportal.session.api.IdentifierType;
import es.in2.issuanceportal.session.api.PersonalIdentifier;
import es.in2.issuanceportal.session.api.Section;
import es.in2.issuanceportal.session.api.SessionId;
import es.in2.issuanceportal.shared.error.ProblemCode;
import es.in2.issuanceportal.shared.error.ProblemException;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailVerificationServiceTest {

    private static final TenantId CGCOM = new TenantId("cgcom");
    private static final String EMAIL = "Elena.Ruiz@example.test";
    private static final String CLIENT = "203.0.113.7";
    private static final Instant START = Instant.parse("2026-11-11T10:00:00Z");

    /** The limit per client address, in the hands of the test. */
    private static final class ClientLimit implements EmailCodeRateLimit {

        private final AtomicInteger consumed = new AtomicInteger();
        private final AtomicInteger givenBack = new AtomicInteger();
        private volatile Duration deniedFor;

        @Override
        public Optional<Duration> tryConsume(String clientAddress) {
            if (deniedFor != null) {
                return Optional.of(deniedFor);
            }
            consumed.incrementAndGet();
            return Optional.empty();
        }

        @Override
        public void giveBack(String clientAddress) {
            givenBack.incrementAndGet();
        }
    }

    private final InMemoryJourneySessions sessions = new InMemoryJourneySessions();
    private final InMemoryJourneyRepository journeys = new InMemoryJourneyRepository(sessions);
    private final MutableClock clock = new MutableClock(START);
    private final RecordingJourneyAuditTrail trail = new RecordingJourneyAuditTrail();
    private final JourneyService journey = new JourneyService(journeys, sessions, trail,
            TransactionOperations.withoutTransaction(), TransactionOperations.withoutTransaction(), clock);
    private final RecordingEmailCodeMailer mailer = new RecordingEmailCodeMailer();
    private final ClientLimit clientLimit = new ClientLimit();
    private final Map<TenantId, PortalConfig> configs = new HashMap<>();
    private final EmailVerificationService service = new EmailVerificationService(journey, journeys, sessions,
            new PortalConfigService(tenant -> Optional.ofNullable(configs.get(tenant))), mailer, clientLimit, trail,
            TransactionOperations.withoutTransaction(), clock);

    // --- AC-01, EC-01, EC-07 ---

    @Test
    @DisplayName("AC-01 — send_entitledHolder_sendsTheCodeAndKeepsItsHashNotTheCode")
    void send_entitledHolder_sendsTheCodeAndKeepsItsHashNotTheCode() {
        configs.put(CGCOM, config(Map.of()));
        SessionId session = entitledJourney();

        EmailVerification sent = service.send(CGCOM, session, EMAIL, EmailLanguage.ES, CLIENT);

        assertThat(mailer.sent()).hasSize(1);
        String code = RecordingEmailCodeMailer.codeOf(mailer.last());
        assertThat(code).matches("\\d{6}");
        EmailVerificationRecord stored = stored(session);
        assertThat(stored.pendingEmail()).isEqualTo(EMAIL);
        assertThat(stored.codeHash()).isEqualTo(EmailVerification.hash(journeyId(session), code))
                .doesNotContain(code);
        assertThat(stored.codeExpiresAt()).isEqualTo(START.plus(Duration.ofMinutes(10)));
        assertThat(stored.attempts()).isZero();
        assertThat(stored.sends()).isEqualTo(1);
        assertThat(stored.lastSentAt()).isEqualTo(START);
        assertThat(sent.pendingEmail()).isEqualTo(EMAIL);
        assertThat(sent.resendAvailableAt()).isEqualTo(START.plusSeconds(180));
        assertThat(sent.sendsLeft()).isEqualTo(2);
        assertThat(sessions.read(CGCOM, session, Section.EMAIL_VERIFICATION).orElseThrow()).doesNotContain(code);
    }

    @Test
    @DisplayName("EC-07 — send_emailWithSpacesAndCapitals_sendsToTheStrippedAddressKeepingCapitals")
    void send_emailWithSpacesAndCapitals_sendsToTheStrippedAddressKeepingCapitals() {
        configs.put(CGCOM, config(Map.of()));
        SessionId session = entitledJourney();

        service.send(CGCOM, session, "  Elena.Ruiz@Example.Test \n", EmailLanguage.ES, CLIENT);

        assertThat(mailer.last().to()).isEqualTo("Elena.Ruiz@Example.Test");
        assertThat(stored(session).pendingEmail()).isEqualTo("Elena.Ruiz@Example.Test");
    }

    @Test
    @DisplayName("AC-07 — send_journeyWithVerifiedEmail_clearsItAndVerifiesNothingUntilTheNewCode")
    void send_journeyWithVerifiedEmail_clearsItAndVerifiesNothingUntilTheNewCode() {
        configs.put(CGCOM, config(Map.of()));
        SessionId session = entitledJourney();
        sessions.write(CGCOM, session, Section.EMAIL, new EmailRecord("old@example.test").toJson());

        service.send(CGCOM, session, EMAIL, EmailLanguage.ES, CLIENT);

        assertThat(sessions.read(CGCOM, session, Section.EMAIL)).isEmpty();
        assertThat(journeys.findById(CGCOM, journeyId(session)).orElseThrow().step())
                .isEqualTo(JourneyStep.ENTITLEMENT_CONFIRMED);
    }

    // --- Preconditions: ES-01, ES-04, ES-05, EC-04 ---

    @Test
    @DisplayName("ES-05 — send_noEntitlement_isStepNotAllowedAndSendsNothing")
    void send_noEntitlement_isStepNotAllowedAndSendsNothing() {
        configs.put(CGCOM, config(Map.of()));
        SessionId session = identifiedJourney();

        assertProblem(() -> service.send(CGCOM, session, EMAIL, EmailLanguage.ES, CLIENT),
                ProblemCode.STEP_NOT_ALLOWED);

        assertNothingSent(session);
    }

    @Test
    void send_noIdentity_isStepNotAllowedAndSendsNothing() {
        configs.put(CGCOM, config(Map.of()));
        SessionId session = journey.start(CGCOM, Optional.empty()).session();

        assertProblem(() -> service.send(CGCOM, session, EMAIL, EmailLanguage.ES, CLIENT),
                ProblemCode.STEP_NOT_ALLOWED);

        assertNothingSent(session);
    }

    @Test
    @DisplayName("EC-04 — send_offerAlreadyDelivered_isStepNotAllowedAndSendsNothing")
    void send_offerAlreadyDelivered_isStepNotAllowedAndSendsNothing() {
        configs.put(CGCOM, config(Map.of()));
        SessionId session = entitledJourney();
        Journey completed = journeys.findById(CGCOM, journeyId(session)).orElseThrow();
        completed.complete(clock.instant());
        journeys.update(CGCOM, completed, JourneyState.IN_PROGRESS);

        assertProblem(() -> service.send(CGCOM, session, EMAIL, EmailLanguage.ES, CLIENT),
                ProblemCode.STEP_NOT_ALLOWED);

        assertThat(mailer.sent()).isEmpty();
        assertThat(clientLimit.consumed).hasValue(0);
    }

    @Test
    @DisplayName("ES-04 — send_withdrawnJourney_isJourneyClosed")
    void send_withdrawnJourney_isJourneyClosed() {
        configs.put(CGCOM, config(Map.of()));
        SessionId session = entitledJourney();
        journey.cancel(CGCOM, session);

        assertThatThrownBy(() -> service.send(CGCOM, session, EMAIL, EmailLanguage.ES, CLIENT))
                .isInstanceOf(JourneyClosedException.class);

        assertThat(mailer.sent()).isEmpty();
    }

    @Test
    @DisplayName("ES-04 — send_inactiveJourney_isClosedAndSendsNothing")
    void send_inactiveJourney_isClosedAndSendsNothing() {
        configs.put(CGCOM, config(Map.of()));
        SessionId session = entitledJourney();
        clock.advance(Journey.INACTIVITY_LIMIT.plusSeconds(1));

        assertThatThrownBy(() -> service.send(CGCOM, session, EMAIL, EmailLanguage.ES, CLIENT))
                .isInstanceOf(JourneyClosedException.class);

        assertThat(mailer.sent()).isEmpty();
    }

    @Test
    @DisplayName("ES-01 — send_noEmail_isEmailRequiredAndCountsForNoLimit")
    void send_noEmail_isEmailRequiredAndCountsForNoLimit() {
        configs.put(CGCOM, config(Map.of()));
        SessionId session = entitledJourney();

        assertProblem(() -> service.send(CGCOM, session, null, EmailLanguage.ES, CLIENT), ProblemCode.EMAIL_REQUIRED);
        assertProblem(() -> service.send(CGCOM, session, "  ", EmailLanguage.ES, CLIENT), ProblemCode.EMAIL_REQUIRED);

        assertNothingSent(session);
        assertThat(clientLimit.consumed).hasValue(0);
        assertThat(trail.emailCodeRequests()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-an-email", "a@b", "two@@example.test"})
    @DisplayName("ES-01 — send_malformedEmail_isEmailInvalidAndCountsForNoLimit")
    void send_malformedEmail_isEmailInvalidAndCountsForNoLimit(String email) {
        configs.put(CGCOM, config(Map.of()));
        SessionId session = entitledJourney();

        assertProblem(() -> service.send(CGCOM, session, email, EmailLanguage.ES, CLIENT), ProblemCode.EMAIL_INVALID);

        assertNothingSent(session);
        assertThat(clientLimit.consumed).hasValue(0);
    }

    @Test
    @DisplayName("ES-01 — send_emailOver254Characters_isEmailInvalid")
    void send_emailOver254Characters_isEmailInvalid() {
        configs.put(CGCOM, config(Map.of()));
        SessionId session = entitledJourney();

        assertProblem(() -> service.send(CGCOM, session, "a".repeat(250) + "@example.test", EmailLanguage.ES, CLIENT),
                ProblemCode.EMAIL_INVALID);

        assertNothingSent(session);
    }

    // --- Limits: AC-04, AC-05 ---

    @Test
    @DisplayName("AC-04 — send_beforeThreeMinutes_isResendTooSoonWithTheSecondsLeftAndKeepsThePreviousCode")
    void send_beforeThreeMinutes_isResendTooSoonWithTheSecondsLeftAndKeepsThePreviousCode() {
        configs.put(CGCOM, config(Map.of()));
        SessionId session = entitledJourney();
        service.send(CGCOM, session, EMAIL, EmailLanguage.ES, CLIENT);
        String firstSection = sessions.read(CGCOM, session, Section.EMAIL_VERIFICATION).orElseThrow();
        clock.advance(Duration.ofSeconds(100));

        assertThatThrownBy(() -> service.send(CGCOM, session, EMAIL, EmailLanguage.ES, CLIENT))
                .isInstanceOfSatisfying(ProblemException.class, e -> {
                    assertThat(e.problem()).isEqualTo(ProblemCode.RESEND_TOO_SOON);
                    assertThat(e.retryAfter()).contains(Duration.ofSeconds(80));
                });

        assertThat(mailer.sent()).hasSize(1);
        assertThat(sessions.read(CGCOM, session, Section.EMAIL_VERIFICATION)).contains(firstSection);
        assertThat(clientLimit.consumed).hasValue(1);
        assertThat(trail.emailCodeRequests()).extracting(event -> event.result())
                .containsExactly(EmailCodeOutcome.SENT, EmailCodeOutcome.RESEND_TOO_SOON);
    }

    @Test
    @DisplayName("AC-04 — send_afterThreeMinutes_sendsANewCodeThatReplacesThePrevious")
    void send_afterThreeMinutes_sendsANewCodeThatReplacesThePrevious() {
        configs.put(CGCOM, config(Map.of()));
        SessionId session = entitledJourney();
        service.send(CGCOM, session, EMAIL, EmailLanguage.ES, CLIENT);
        String firstHash = stored(session).codeHash();
        clock.advance(Duration.ofSeconds(180));

        EmailVerification second = service.send(CGCOM, session, "other@example.test", EmailLanguage.ES, CLIENT);

        assertThat(mailer.sent()).hasSize(2);
        assertThat(mailer.last().to()).isEqualTo("other@example.test");
        EmailVerificationRecord stored = stored(session);
        assertThat(stored.codeHash()).isNotEqualTo(firstHash)
                .isEqualTo(EmailVerification.hash(journeyId(session), RecordingEmailCodeMailer.codeOf(mailer.last())));
        assertThat(stored.sends()).isEqualTo(2);
        assertThat(stored.attempts()).isZero();
        assertThat(stored.pendingEmail()).isEqualTo("other@example.test");
        assertThat(second.sendsLeft()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-05 — send_afterThreeCodes_isTooManyCodesWithNoWaitAndSendsNothing")
    void send_afterThreeCodes_isTooManyCodesWithNoWaitAndSendsNothing() {
        configs.put(CGCOM, config(Map.of()));
        SessionId session = entitledJourney();
        for (int i = 0; i < 3; i++) {
            service.send(CGCOM, session, EMAIL, EmailLanguage.ES, CLIENT);
            clock.advance(Duration.ofSeconds(181));
        }

        assertThatThrownBy(() -> service.send(CGCOM, session, EMAIL, EmailLanguage.ES, CLIENT))
                .isInstanceOfSatisfying(ProblemException.class, e -> {
                    assertThat(e.problem()).isEqualTo(ProblemCode.TOO_MANY_CODES);
                    assertThat(e.retryAfter()).isEmpty();
                });

        assertThat(mailer.sent()).hasSize(3);
        assertThat(clientLimit.consumed).hasValue(3);
        assertThat(trail.emailCodeRequests().getLast().result()).isEqualTo(EmailCodeOutcome.TOO_MANY_CODES);
    }

    @Test
    @DisplayName("AC-05 — send_clientAddressOverItsLimit_isTooManyRequestsWithRetryAfterAndSendsNothing")
    void send_clientAddressOverItsLimit_isTooManyRequestsWithRetryAfterAndSendsNothing() {
        configs.put(CGCOM, config(Map.of()));
        SessionId session = entitledJourney();
        clientLimit.deniedFor = Duration.ofMinutes(42);

        assertThatThrownBy(() -> service.send(CGCOM, session, EMAIL, EmailLanguage.ES, CLIENT))
                .isInstanceOfSatisfying(ProblemException.class, e -> {
                    assertThat(e.problem()).isEqualTo(ProblemCode.TOO_MANY_REQUESTS);
                    assertThat(e.retryAfter()).contains(Duration.ofMinutes(42));
                });

        assertNothingSent(session);
        assertThat(trail.emailCodeRequests()).extracting(event -> event.result())
                .containsExactly(EmailCodeOutcome.TOO_MANY_REQUESTS);
    }

    @Test
    void send_journeyLimitTrips_doesNotSpendTheClientAddressBudget() {
        configs.put(CGCOM, config(Map.of()));
        SessionId session = entitledJourney();
        service.send(CGCOM, session, EMAIL, EmailLanguage.ES, CLIENT);

        assertThatThrownBy(() -> service.send(CGCOM, session, EMAIL, EmailLanguage.ES, CLIENT))
                .isInstanceOf(ProblemException.class);

        assertThat(clientLimit.consumed).hasValue(1);
    }

    // --- AC-06, NFR-1 ---

    @Test
    @DisplayName("AC-06 — send_mailerFails_isEmailUnavailableWritesNothingAndGivesTheAddressItsSlotBack")
    void send_mailerFails_isEmailUnavailableWritesNothingAndGivesTheAddressItsSlotBack() {
        configs.put(CGCOM, config(Map.of()));
        SessionId session = entitledJourney();
        sessions.write(CGCOM, session, Section.EMAIL, new EmailRecord("old@example.test").toJson());
        mailer.failFromNowOn();

        assertProblem(() -> service.send(CGCOM, session, EMAIL, EmailLanguage.ES, CLIENT),
                ProblemCode.EMAIL_UNAVAILABLE);

        assertThat(sessions.read(CGCOM, session, Section.EMAIL_VERIFICATION)).isEmpty();
        assertThat(sessions.read(CGCOM, session, Section.EMAIL)).isPresent();
        assertThat(clientLimit.consumed).hasValue(1);
        assertThat(clientLimit.givenBack).hasValue(1);
        assertThat(trail.emailCodeRequests()).extracting(event -> event.result())
                .containsExactly(EmailCodeOutcome.EMAIL_UNAVAILABLE);
    }

    @Test
    @DisplayName("AC-06 — send_mailerFailsOnAResend_keepsThePreviousCodeAndCountsNoSend")
    void send_mailerFailsOnAResend_keepsThePreviousCodeAndCountsNoSend() {
        configs.put(CGCOM, config(Map.of()));
        SessionId session = entitledJourney();
        service.send(CGCOM, session, EMAIL, EmailLanguage.ES, CLIENT);
        String firstSection = sessions.read(CGCOM, session, Section.EMAIL_VERIFICATION).orElseThrow();
        clock.advance(Duration.ofSeconds(200));
        mailer.failFromNowOn();

        assertProblem(() -> service.send(CGCOM, session, EMAIL, EmailLanguage.ES, CLIENT),
                ProblemCode.EMAIL_UNAVAILABLE);

        assertThat(sessions.read(CGCOM, session, Section.EMAIL_VERIFICATION)).contains(firstSection);
        assertThat(stored(session).sends()).isEqualTo(1);
    }

    // --- EC-06 ---

    @Test
    @DisplayName("EC-06 — send_englishWithPortalName_usesTheEnglishTemplateAndTheTenantsName")
    void send_englishWithPortalName_usesTheEnglishTemplateAndTheTenantsName() {
        configs.put(CGCOM, config(Map.of(
                "es", Map.of("portal", Map.of("app_name", "Portal DoctorID")),
                "en", Map.of("portal", Map.of("app_name", "DoctorID Portal")))));
        SessionId session = entitledJourney();

        service.send(CGCOM, session, EMAIL, EmailLanguage.EN, CLIENT);

        assertThat(mailer.last().subject()).isEqualTo("Your verification code");
        assertThat(mailer.last().body()).contains("DoctorID Portal").doesNotContain("Portal DoctorID");
    }

    @Test
    @DisplayName("EC-06 — send_spanishWithPortalName_usesTheSpanishNameOfTheTenant")
    void send_spanishWithPortalName_usesTheSpanishNameOfTheTenant() {
        configs.put(CGCOM, config(Map.of(
                "es", Map.of("portal", Map.of("app_name", "Portal DoctorID")))));
        SessionId session = entitledJourney();

        service.send(CGCOM, session, EMAIL, EmailLanguage.ES, CLIENT);

        assertThat(mailer.last().subject()).isEqualTo("Tu código de verificación");
        assertThat(mailer.last().body()).contains("Portal DoctorID");
    }

    @Test
    @DisplayName("EC-06 — send_noAppNameInTheLanguage_usesTheDefaultNameOfThatLanguage")
    void send_noAppNameInTheLanguage_usesTheDefaultNameOfThatLanguage() {
        configs.put(CGCOM, config(Map.of("es", Map.of("portal", Map.of("app_name", "Portal DoctorID")))));
        SessionId session = entitledJourney();

        service.send(CGCOM, session, EMAIL, EmailLanguage.EN, CLIENT);

        assertThat(mailer.last().body()).contains("Issuance portal");
    }

    @Test
    @DisplayName("EC-06 — send_noTextsAtAll_usesTheSpanishDefaultName")
    void send_noTextsAtAll_usesTheSpanishDefaultName() {
        configs.put(CGCOM, config(Map.of()));
        SessionId session = entitledJourney();

        service.send(CGCOM, session, EMAIL, EmailLanguage.ES, CLIENT);

        assertThat(mailer.last().body()).contains("Portal de emisión");
    }

    @Test
    void send_tenantWithoutConfiguration_isConfigUnavailableAndSpendsNoBudget() {
        SessionId session = entitledJourney();

        assertProblem(() -> service.send(CGCOM, session, EMAIL, EmailLanguage.ES, CLIENT),
                ProblemCode.CONFIG_UNAVAILABLE);

        assertNothingSent(session);
        assertThat(clientLimit.consumed).hasValue(0);
    }

    // --- Trail and invalid state ---

    @Test
    @DisplayName("AC-10 — send_anyOutcome_traceCarriesTheJourneyAndTheResultButNoEmailCodeOrHash")
    void send_anyOutcome_traceCarriesTheJourneyAndTheResultButNoEmailCodeOrHash() {
        configs.put(CGCOM, config(Map.of()));
        SessionId session = entitledJourney();

        service.send(CGCOM, session, EMAIL, EmailLanguage.ES, CLIENT);

        assertThat(trail.emailCodeRequests()).singleElement().satisfies(event -> {
            assertThat(event.journeyId()).isEqualTo(journeyId(session));
            assertThat(event.result()).isEqualTo(EmailCodeOutcome.SENT);
            assertThat(event.occurredAt()).isEqualTo(START);
            assertThat(event.toString()).doesNotContain(EMAIL, stored(session).codeHash(),
                    RecordingEmailCodeMailer.codeOf(mailer.last()));
        });
    }

    @Test
    void send_trailFails_stillSendsTheCode() {
        configs.put(CGCOM, config(Map.of()));
        SessionId session = entitledJourney();
        trail.failFromNowOn();

        service.send(CGCOM, session, EMAIL, EmailLanguage.ES, CLIENT);

        assertThat(mailer.sent()).hasSize(1);
    }

    @Test
    void send_invalidStoredVerification_isServiceUnavailableAndSendsNothing() {
        configs.put(CGCOM, config(Map.of()));
        SessionId session = entitledJourney();
        sessions.write(CGCOM, session, Section.EMAIL_VERIFICATION, "{\"sends\":3}");

        assertProblem(() -> service.send(CGCOM, session, EMAIL, EmailLanguage.ES, CLIENT),
                ProblemCode.SERVICE_UNAVAILABLE);

        assertThat(mailer.sent()).isEmpty();
    }

    // --- the check: AC-02, AC-03, AC-04, AC-07, ES-02, ES-03, ES-04 ---

    private String sendCode(SessionId session) {
        service.send(CGCOM, session, EMAIL, EmailLanguage.ES, CLIENT);
        return RecordingEmailCodeMailer.codeOf(mailer.last());
    }

    private String wrongCodeFor(String code) {
        return code.equals("000000") ? "000001" : "000000";
    }

    @Test
    @DisplayName("AC-02 — check_rightCode_verifiesTheEmailDropsTheCodeAndReachesTheStep")
    void check_rightCode_verifiesTheEmailDropsTheCodeAndReachesTheStep() {
        configs.put(CGCOM, config(Map.of()));
        SessionId session = entitledJourney();
        String code = sendCode(session);
        clock.advance(Duration.ofSeconds(30));

        service.check(CGCOM, session, code);

        assertThat(EmailRecord.fromJson(sessions.read(CGCOM, session, Section.EMAIL).orElseThrow()).address())
                .isEqualTo(EMAIL);
        EmailVerificationRecord stored = stored(session);
        assertThat(stored.codeHash()).isNull();
        assertThat(stored.codeExpiresAt()).isNull();
        assertThat(stored.sends()).isEqualTo(1);
        assertThat(stored.lastSentAt()).isEqualTo(START);
        assertThat(journeys.findById(CGCOM, journeyId(session)).orElseThrow().step())
                .isEqualTo(JourneyStep.EMAIL_VERIFIED);
        assertThat(trail.emailCodeChecks()).extracting(event -> event.result())
                .containsExactly(JourneyAuditTrail.EmailCodeCheck.VERIFIED);
        assertThat(trail.events().getLast().kind()).isEqualTo(JourneyAuditTrail.Kind.STEP_REACHED);
        assertThat(trail.events().getLast().step()).isEqualTo(JourneyStep.EMAIL_VERIFIED);
    }

    @Test
    @DisplayName("AC-02 — check_afterItWasVerified_theCodeNoLongerServes")
    void check_afterItWasVerified_theCodeNoLongerServes() {
        configs.put(CGCOM, config(Map.of()));
        SessionId session = entitledJourney();
        String code = sendCode(session);
        service.check(CGCOM, session, code);

        assertProblem(() -> service.check(CGCOM, session, code), ProblemCode.STEP_NOT_ALLOWED);
    }

    @Test
    @DisplayName("AC-03 — check_wrongCode_isInvalidCodeAndKeepsTheSpentAttempt")
    void check_wrongCode_isInvalidCodeAndKeepsTheSpentAttempt() {
        configs.put(CGCOM, config(Map.of()));
        SessionId session = entitledJourney();
        String code = sendCode(session);

        assertProblem(() -> service.check(CGCOM, session, wrongCodeFor(code)), ProblemCode.INVALID_CODE);

        assertThat(stored(session).attempts()).isEqualTo(1);
        assertThat(sessions.read(CGCOM, session, Section.EMAIL)).isEmpty();
        assertThat(journeys.findById(CGCOM, journeyId(session)).orElseThrow().step())
                .isEqualTo(JourneyStep.ENTITLEMENT_CONFIRMED);
        assertThat(trail.emailCodeChecks()).extracting(event -> event.result())
                .containsExactly(JourneyAuditTrail.EmailCodeCheck.INVALID_CODE);
    }

    @Test
    @DisplayName("AC-03 — check_fiveWrongCodes_theSixthIsTooManyAttemptsEvenWithTheRightOne")
    void check_fiveWrongCodes_theSixthIsTooManyAttemptsEvenWithTheRightOne() {
        configs.put(CGCOM, config(Map.of()));
        SessionId session = entitledJourney();
        String code = sendCode(session);
        for (int i = 0; i < 5; i++) {
            assertProblem(() -> service.check(CGCOM, session, wrongCodeFor(code)), ProblemCode.INVALID_CODE);
        }

        assertProblem(() -> service.check(CGCOM, session, code), ProblemCode.TOO_MANY_ATTEMPTS);

        assertThat(stored(session).attempts()).isEqualTo(5);
        assertThat(sessions.read(CGCOM, session, Section.EMAIL)).isEmpty();
    }

    @Test
    @DisplayName("AC-03 — check_afterTenMinutes_isExpiredCodeEvenWithTheRightOne")
    void check_afterTenMinutes_isExpiredCodeEvenWithTheRightOne() {
        configs.put(CGCOM, config(Map.of()));
        SessionId session = entitledJourney();
        String code = sendCode(session);
        clock.advance(Duration.ofMinutes(10));
        // The journey stays active: the holder keeps using the page.
        journey.current(CGCOM, session);

        assertProblem(() -> service.check(CGCOM, session, code), ProblemCode.EXPIRED_CODE);

        assertThat(stored(session).attempts()).isZero();
        assertThat(sessions.read(CGCOM, session, Section.EMAIL)).isEmpty();
    }

    @Test
    @DisplayName("AC-04 — check_codeReplacedByAResend_isExpiredCodeAndTheNewOneVerifies")
    void check_codeReplacedByAResend_isExpiredCodeAndTheNewOneVerifies() {
        configs.put(CGCOM, config(Map.of()));
        SessionId session = entitledJourney();
        String first = sendCode(session);
        clock.advance(Duration.ofSeconds(181));
        String second = sendCode(session);

        if (!first.equals(second)) {
            assertProblem(() -> service.check(CGCOM, session, first), ProblemCode.EXPIRED_CODE);
        }
        service.check(CGCOM, session, second);

        assertThat(sessions.read(CGCOM, session, Section.EMAIL)).isPresent();
    }

    @Test
    @DisplayName("AC-07 — check_newCodeToAnotherEmail_verifiesTheNewEmailAndItIsTheOneKept")
    void check_newCodeToAnotherEmail_verifiesTheNewEmailAndItIsTheOneKept() {
        configs.put(CGCOM, config(Map.of()));
        SessionId session = entitledJourney();
        service.check(CGCOM, session, sendCode(session));
        clock.advance(Duration.ofSeconds(181));
        service.send(CGCOM, session, "new.address@example.test", EmailLanguage.ES, CLIENT);
        assertThat(sessions.read(CGCOM, session, Section.EMAIL)).isEmpty();

        service.check(CGCOM, session, RecordingEmailCodeMailer.codeOf(mailer.last()));

        assertThat(EmailRecord.fromJson(sessions.read(CGCOM, session, Section.EMAIL).orElseThrow()).address())
                .isEqualTo("new.address@example.test");
        // The step only goes forward: it was email_verified before and stays so.
        assertThat(journeys.findById(CGCOM, journeyId(session)).orElseThrow().step())
                .isEqualTo(JourneyStep.EMAIL_VERIFIED);
    }

    @Test
    @DisplayName("AC-07 — check_afterANewCodeWasSent_theStepNeverGoesBack")
    void check_afterANewCodeWasSent_theStepNeverGoesBack() {
        configs.put(CGCOM, config(Map.of()));
        SessionId session = entitledJourney();
        service.check(CGCOM, session, sendCode(session));
        clock.advance(Duration.ofSeconds(181));

        service.send(CGCOM, session, "new.address@example.test", EmailLanguage.ES, CLIENT);

        assertThat(journeys.findById(CGCOM, journeyId(session)).orElseThrow().step())
                .isEqualTo(JourneyStep.EMAIL_VERIFIED);
        assertThat(sessions.read(CGCOM, session, Section.EMAIL)).isEmpty();
    }

    @Test
    @DisplayName("ES-03 — check_neverSent_isStepNotAllowed")
    void check_neverSent_isStepNotAllowed() {
        configs.put(CGCOM, config(Map.of()));
        SessionId session = entitledJourney();

        assertProblem(() -> service.check(CGCOM, session, "123456"), ProblemCode.STEP_NOT_ALLOWED);

        assertThat(trail.emailCodeChecks()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "12345", "1234567", "12345a", " 123456", "abcdef"})
    @DisplayName("ES-02 — check_malformedCode_isBadRequestAndSpendsNoAttempt")
    void check_malformedCode_isBadRequestAndSpendsNoAttempt(String code) {
        configs.put(CGCOM, config(Map.of()));
        SessionId session = entitledJourney();
        sendCode(session);

        assertProblem(() -> service.check(CGCOM, session, code), ProblemCode.BAD_REQUEST);
        assertProblem(() -> service.check(CGCOM, session, null), ProblemCode.BAD_REQUEST);

        assertThat(stored(session).attempts()).isZero();
        assertThat(trail.emailCodeChecks()).isEmpty();
    }

    @Test
    @DisplayName("ES-04 — check_withdrawnJourney_isClosed")
    void check_withdrawnJourney_isClosed() {
        configs.put(CGCOM, config(Map.of()));
        SessionId session = entitledJourney();
        sendCode(session);
        journey.cancel(CGCOM, session);

        assertThatThrownBy(() -> service.check(CGCOM, session, "123456")).isInstanceOf(JourneyClosedException.class);
    }

    @Test
    @DisplayName("ES-04 — check_offerAlreadyDelivered_isStepNotAllowed")
    void check_offerAlreadyDelivered_isStepNotAllowed() {
        configs.put(CGCOM, config(Map.of()));
        SessionId session = entitledJourney();
        String code = sendCode(session);
        Journey completed = journeys.findById(CGCOM, journeyId(session)).orElseThrow();
        completed.complete(clock.instant());
        journeys.update(CGCOM, completed, JourneyState.IN_PROGRESS);

        assertProblem(() -> service.check(CGCOM, session, code), ProblemCode.STEP_NOT_ALLOWED);
    }

    @Test
    @DisplayName("AC-10 — check_anyOutcome_traceCarriesNeitherEmailNorCodeNorHash")
    void check_anyOutcome_traceCarriesNeitherEmailNorCodeNorHash() {
        configs.put(CGCOM, config(Map.of()));
        SessionId session = entitledJourney();
        String code = sendCode(session);
        String hash = stored(session).codeHash();
        assertProblem(() -> service.check(CGCOM, session, wrongCodeFor(code)), ProblemCode.INVALID_CODE);
        service.check(CGCOM, session, code);

        assertThat(trail.emailCodeChecks()).hasSize(2).allSatisfy(event ->
                assertThat(event.toString()).doesNotContain(EMAIL, code, hash));
        assertThat(trail.events()).allSatisfy(event -> assertThat(event.toString()).doesNotContain(EMAIL, code, hash));
    }

    @Test
    void check_trailFails_stillVerifies() {
        configs.put(CGCOM, config(Map.of()));
        SessionId session = entitledJourney();
        String code = sendCode(session);
        trail.failFromNowOn();

        service.check(CGCOM, session, code);

        assertThat(sessions.read(CGCOM, session, Section.EMAIL)).isPresent();
    }

    // --- helpers ---

    private void assertNothingSent(SessionId session) {
        assertThat(mailer.sent()).isEmpty();
        assertThat(sessions.read(CGCOM, session, Section.EMAIL_VERIFICATION)).isEmpty();
    }

    private static void assertProblem(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ProblemCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ProblemException.class,
                e -> assertThat(e.problem()).isEqualTo(code));
    }

    private EmailVerificationRecord stored(SessionId session) {
        return EmailVerificationRecord.fromJson(
                sessions.read(CGCOM, session, Section.EMAIL_VERIFICATION).orElseThrow());
    }

    private UUID journeyId(SessionId session) {
        return sessions.journeyOf(CGCOM, session).orElseThrow();
    }

    private SessionId identifiedJourney() {
        SessionId session = journey.start(CGCOM, Optional.empty()).session();
        sessions.write(CGCOM, session, Section.IDENTITY, new HolderIdentity("Elena", "Ruiz Prieto",
                new PersonalIdentifier(IdentifierType.DNI, "00000001R"), "elena.token@example.test").toJson());
        journey.reach(CGCOM, session, JourneyProgress.Step.IDENTIFIED);
        return session;
    }

    private SessionId entitledJourney() {
        SessionId session = identifiedJourney();
        sessions.write(CGCOM, session, Section.ENTITLEMENT, new EntitlementRecord(IdentifierType.DNI,
                Map.of("legally_entitled", true),
                new EntitlementRecord.Preview("DoctorID", List.of(), null, true), clock.instant()).toJson());
        journey.reach(CGCOM, session, JourneyProgress.Step.ENTITLEMENT_CONFIRMED);
        return session;
    }

    private static PortalConfig config(Map<String, Object> texts) {
        return new PortalConfig(texts, new LegalLinks(null, null, null, null),
                new Support(null, null, null, null), "https://cgcom.test/wallet", null);
    }
}
