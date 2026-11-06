package es.in2.issuanceportal.journey.application;

import es.in2.issuanceportal.MutableClock;
import es.in2.issuanceportal.journey.InMemoryIssuanceApi;
import es.in2.issuanceportal.journey.InMemoryJourneyRepository;
import es.in2.issuanceportal.journey.InMemoryOnboardingTickets;
import es.in2.issuanceportal.journey.RecordingJourneyAuditTrail;
import es.in2.issuanceportal.journey.api.JourneyProgress;
import es.in2.issuanceportal.journey.application.port.IssuanceApi;
import es.in2.issuanceportal.journey.application.port.IssuanceRejectedException;
import es.in2.issuanceportal.journey.application.port.IssuanceUnavailableException;
import es.in2.issuanceportal.journey.application.port.IssuanceResult;
import es.in2.issuanceportal.journey.application.port.JourneyAuditTrail;
import es.in2.issuanceportal.journey.application.port.OnboardingTicketException;
import es.in2.issuanceportal.journey.application.port.SystemTokenUnavailableException;
import es.in2.issuanceportal.journey.domain.Journey;
import es.in2.issuanceportal.journey.domain.JourneyClosedException;
import es.in2.issuanceportal.journey.domain.JourneyState;
import es.in2.issuanceportal.journey.domain.JourneyStep;
import es.in2.issuanceportal.journey.domain.WithdrawalReason;
import es.in2.issuanceportal.journey.domain.config.LegalLinks;
import es.in2.issuanceportal.journey.domain.config.Onboarding;
import es.in2.issuanceportal.journey.domain.config.PortalConfig;
import es.in2.issuanceportal.journey.domain.config.ServiceSettings;
import es.in2.issuanceportal.journey.domain.config.Support;
import es.in2.issuanceportal.session.InMemoryJourneySessions;
import es.in2.issuanceportal.session.api.EmailRecord;
import es.in2.issuanceportal.session.api.EntitlementRecord;
import es.in2.issuanceportal.session.api.HolderIdentity;
import es.in2.issuanceportal.session.api.IdentifierType;
import es.in2.issuanceportal.session.api.IssuanceRecord;
import es.in2.issuanceportal.session.api.PersonalIdentifier;
import es.in2.issuanceportal.session.api.Section;
import es.in2.issuanceportal.session.api.SessionId;
import es.in2.issuanceportal.shared.error.ProblemCode;
import es.in2.issuanceportal.shared.error.ProblemException;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IssuanceServiceTest {

    private static final TenantId CGCOM = new TenantId("cgcom");
    private static final String EMAIL = "elena.ruiz@example.test";
    private static final Map<String, String> DOCTORID_PLACEMENT = Map.of(
            "givenName", "identity.given_name",
            "familyName", "identity.family_name",
            "personal_administrative_number", "authentic_source.personal_administrative_number",
            "medical_board", "authentic_source.medical_board",
            "medical_speciality", "authentic_source.medical_speciality",
            "legally_entitled", "authentic_source.legally_entitled");
    private static final Map<String, Object> CLAIMS = Map.of(
            "personal_administrative_number", "280000001",
            "medical_board", List.of("Colegio de Medicos de Prueba"),
            "medical_speciality", List.of("Cardiology"),
            "legally_entitled", true,
            "contact_email", "source@example.test");

    private final InMemoryJourneySessions sessions = new InMemoryJourneySessions();
    private final InMemoryJourneyRepository journeys = new InMemoryJourneyRepository(sessions);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-05T10:00:00Z"));
    private final RecordingJourneyAuditTrail trail = new RecordingJourneyAuditTrail();
    private final JourneyService journey = new JourneyService(journeys, sessions, trail,
            TransactionOperations.withoutTransaction(), TransactionOperations.withoutTransaction(), clock);
    private final InMemoryIssuanceApi api = new InMemoryIssuanceApi(clock);
    private final Map<TenantId, PortalConfig> configs = new HashMap<>();
    private final AtomicBoolean inTransaction = new AtomicBoolean();
    private final AtomicBoolean askedInsideTransaction = new AtomicBoolean();
    private final AtomicBoolean ticketAskedInsideTransaction = new AtomicBoolean();
    private final InMemoryOnboardingTickets tickets = new InMemoryOnboardingTickets(clock);
    private IssuanceApi issuer = api;
    private final IssuanceService service = new IssuanceService(journey, journeys, sessions,
            new PortalConfigService(tenant -> Optional.ofNullable(configs.get(tenant))),
            (tenant, key, request) -> {
                askedInsideTransaction.compareAndSet(false, inTransaction.get());
                return issuer.issue(tenant, key, request);
            },
            (tenant, url, email, offer, expiresIn) -> {
                ticketAskedInsideTransaction.compareAndSet(false, inTransaction.get());
                return tickets.request(tenant, url, email, offer, expiresIn);
            },
            trail, new TrackedTransactions(), clock);

    /** A transaction that only says whether one is open, so a test can tell where the external call is made. */
    private final class TrackedTransactions implements TransactionOperations {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            inTransaction.set(true);
            try {
                return action.doInTransaction(new SimpleTransactionStatus());
            } finally {
                inTransaction.set(false);
            }
        }
    }

    // --- AC-03 (without the ticket), AC-02 ---

    @Test
    @DisplayName("AC-03 — confirm_entitledHolder_requestsIssuanceWithThePlacedPayloadAndCompletes")
    void confirm_entitledHolder_requestsIssuanceWithThePlacedPayloadAndCompletes() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        SessionId session = entitledJourney();

        IssuanceService.Offer offer = service.confirm(CGCOM, session, EMAIL);

        assertThat(api.calls()).hasSize(1);
        InMemoryIssuanceApi.Call call = api.calls().getFirst();
        assertThat(call.tenant()).isEqualTo(CGCOM);
        assertThat(call.request().credentialConfigurationId()).isEqualTo("doctorid.sd.1");
        assertThat(call.request().email()).isEqualTo(EMAIL);
        assertThat(call.request().delivery()).isEqualTo("ui");
        assertThat(call.request().grantType()).isEqualTo("authorization_code");
        assertThat(call.request().payload()).containsOnlyKeys("givenName", "familyName",
                "personal_administrative_number", "medical_board", "medical_speciality", "legally_entitled")
                .containsEntry("givenName", "Elena").containsEntry("familyName", "Ruiz Prieto")
                .containsEntry("legally_entitled", true);
        assertThat(call.request().payload().toString()).doesNotContain("00000001R", EMAIL, "source@example.test");
        Journey stored = journeys.findById(CGCOM, journeyOf(session)).orElseThrow();
        assertThat(stored.state()).isEqualTo(JourneyState.COMPLETED);
        assertThat(stored.step()).isEqualTo(JourneyStep.OFFER_OBTAINED);
        assertThat(stored.issuanceProcessId()).isPresent();
        assertThat(offer.credentialOfferUri()).startsWith("openid-credential-offer://");
        assertThat(offer.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(10)));
        assertThat(offer.toString()).doesNotContain("openid-credential-offer");
    }

    @Test
    @DisplayName("AC-03 — confirm_entitledHolder_keepsKeyOfferAndEmailInTheSession")
    void confirm_entitledHolder_keepsKeyOfferAndEmailInTheSession() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        SessionId session = entitledJourney();

        IssuanceService.Offer offer = service.confirm(CGCOM, session, "  " + EMAIL + " ");

        IssuanceRecord issuance = IssuanceRecord.fromJson(sessions.read(CGCOM, session, Section.ISSUANCE).orElseThrow());
        assertThat(issuance.idempotencyKey()).isEqualTo(api.calls().getFirst().idempotencyKey());
        assertThat(issuance.credentialOfferUri()).isEqualTo(offer.credentialOfferUri());
        assertThat(issuance.offerExpiresAt()).isEqualTo(offer.expiresAt());
        assertThat(issuance.onboarding()).isEmpty();
        assertThat(EmailRecord.fromJson(sessions.read(CGCOM, session, Section.EMAIL).orElseThrow()).address())
                .isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("AC-03 — confirm_entitledHolder_tracesTheRequestAndTheCompletionWithoutPersonalData")
    void confirm_entitledHolder_tracesTheRequestAndTheCompletionWithoutPersonalData() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        SessionId session = entitledJourney();

        service.confirm(CGCOM, session, EMAIL);

        assertThat(trail.issuanceRequests()).singleElement().satisfies(event -> {
            assertThat(event.result()).isEqualTo(JourneyAuditTrail.IssuanceOutcome.ISSUED);
            assertThat(event.journeyId()).isEqualTo(journeyOf(session));
            assertThat(event.issuanceProcessId()).isPresent();
            assertThat(event.toString()).doesNotContain(EMAIL, "openid-credential-offer");
        });
        assertThat(trail.events().getLast().kind()).isEqualTo(JourneyAuditTrail.Kind.COMPLETED);
        assertThat(trail.events().getLast().step()).isEqualTo(JourneyStep.OFFER_OBTAINED);
    }

    @Test
    void confirm_auditTrailFails_stillAnswersWithTheOffer() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        SessionId session = entitledJourney();
        trail.failFromNowOn();

        assertThat(service.confirm(CGCOM, session, EMAIL).credentialOfferUri()).isNotBlank();
    }

    @Test
    void confirm_entitledHolder_doesNotAskTheIssuerInsideATransaction() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        SessionId session = entitledJourney();

        service.confirm(CGCOM, session, EMAIL);

        assertThat(api.calls()).hasSize(1);
        assertThat(askedInsideTransaction).isFalse();
    }

    // --- AC-01 ---

    @Test
    @DisplayName("AC-01 — confirm_noEntitlement_isStepNotAllowedAndNothingChanges")
    void confirm_noEntitlement_isStepNotAllowedAndNothingChanges() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        SessionId session = identifiedJourney();
        Journey before = journeys.findById(CGCOM, journeyOf(session)).orElseThrow();

        assertThatThrownBy(() -> service.confirm(CGCOM, session, EMAIL)).satisfies(e -> assertProblem(e,
                ProblemCode.STEP_NOT_ALLOWED));

        assertThat(api.calls()).isEmpty();
        assertThat(sessions.read(CGCOM, session, Section.ISSUANCE)).isEmpty();
        assertThat(sessions.read(CGCOM, session, Section.EMAIL)).isEmpty();
        Journey after = journeys.findById(CGCOM, before.id()).orElseThrow();
        assertThat(after.state()).isEqualTo(JourneyState.IN_PROGRESS);
        assertThat(after.step()).isEqualTo(JourneyStep.IDENTIFIED);
        assertThat(after.issuanceProcessId()).isEmpty();
    }

    @Test
    @DisplayName("AC-01 — confirm_noIdentity_isStepNotAllowed")
    void confirm_noIdentity_isStepNotAllowed() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        SessionId session = journey.start(CGCOM, Optional.empty()).session();
        sessions.write(CGCOM, session, Section.ENTITLEMENT, entitlement().toJson());

        assertThatThrownBy(() -> service.confirm(CGCOM, session, EMAIL)).satisfies(e -> assertProblem(e,
                ProblemCode.STEP_NOT_ALLOWED));

        assertThat(api.calls()).isEmpty();
    }

    // --- EC-01 ---

    @Test
    @DisplayName("EC-01 — confirm_missingEmail_isEmailRequiredAndNoSystemIsCalled")
    void confirm_missingEmail_isEmailRequiredAndNoSystemIsCalled() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        SessionId session = entitledJourney();

        for (String email : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> service.confirm(CGCOM, session, email)).satisfies(e -> assertProblem(e,
                    ProblemCode.EMAIL_REQUIRED));
        }

        assertNothingAsked(session);
    }

    @Test
    @DisplayName("EC-01 — confirm_emailWithoutEmailShape_isEmailInvalidAndNoSystemIsCalled")
    void confirm_emailWithoutEmailShape_isEmailInvalidAndNoSystemIsCalled() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        SessionId session = entitledJourney();

        for (String email : new String[] {"elena", "elena@", "@example.test", "elena@example", "a b@example.test"}) {
            assertThatThrownBy(() -> service.confirm(CGCOM, session, email)).satisfies(e -> assertProblem(e,
                    ProblemCode.EMAIL_INVALID));
        }

        assertNothingAsked(session);
    }

    // --- ES-04 ---

    @Test
    @DisplayName("ES-04 — confirm_incompleteConfiguration_isConfigUnavailableAndNoSystemIsCalled")
    void confirm_incompleteConfiguration_isConfigUnavailableAndNoSystemIsCalled() {
        SessionId session = entitledJourney();
        List<PortalConfig> incomplete = List.of(
                config(null, DOCTORID_PLACEMENT, Onboarding.DISABLED),
                config(" ", DOCTORID_PLACEMENT, Onboarding.DISABLED),
                config("doctorid.sd.1", Map.of(), Onboarding.DISABLED),
                config("doctorid.sd.1", DOCTORID_PLACEMENT, new Onboarding(true, null)));

        for (PortalConfig config : incomplete) {
            configs.put(CGCOM, config);
            assertThatThrownBy(() -> service.confirm(CGCOM, session, EMAIL)).satisfies(e -> assertProblem(e,
                    ProblemCode.CONFIG_UNAVAILABLE));
        }

        assertNothingAsked(session);
    }

    @Test
    @DisplayName("ES-04 — confirm_tenantWithoutConfiguration_isConfigUnavailable")
    void confirm_tenantWithoutConfiguration_isConfigUnavailable() {
        SessionId session = entitledJourney();

        assertThatThrownBy(() -> service.confirm(CGCOM, session, EMAIL)).satisfies(e -> assertProblem(e,
                ProblemCode.CONFIG_UNAVAILABLE));

        assertNothingAsked(session);
    }

    @Test
    @DisplayName("ES-04 — confirm_placementPointsAtDataTheJourneyDoesNotHave_isConfigUnavailable")
    void confirm_placementPointsAtDataTheJourneyDoesNotHave_isConfigUnavailable() {
        configs.put(CGCOM, config("doctorid.sd.1", Map.of("givenName", "identity.given_name",
                "unknown", "authentic_source.not_translated"), Onboarding.DISABLED));
        SessionId session = entitledJourney();

        assertThatThrownBy(() -> service.confirm(CGCOM, session, EMAIL)).satisfies(e -> assertProblem(e,
                ProblemCode.CONFIG_UNAVAILABLE));

        assertNothingAsked(session);
    }

    // --- EC-09, ES-06 ---

    @Test
    @DisplayName("EC-09 — confirm_completedJourney_isJourneyClosedAndNoSystemIsCalled")
    void confirm_completedJourney_isJourneyClosedAndNoSystemIsCalled() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        SessionId session = entitledJourney();
        service.confirm(CGCOM, session, EMAIL);

        assertThatThrownBy(() -> service.confirm(CGCOM, session, EMAIL)).isInstanceOf(JourneyClosedException.class);

        assertThat(api.calls()).hasSize(1);
    }

    @Test
    @DisplayName("ES-06 — confirm_sessionWithoutJourney_isJourneyClosed")
    void confirm_sessionWithoutJourney_isJourneyClosed() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));

        assertThatThrownBy(() -> service.confirm(CGCOM, SessionId.generate(), EMAIL))
                .isInstanceOf(JourneyClosedException.class);

        assertThat(api.calls()).isEmpty();
    }

    @Test
    void confirm_withdrawnJourney_isJourneyClosed() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        SessionId session = entitledJourney();
        journey.cancel(CGCOM, session);

        assertThatThrownBy(() -> service.confirm(CGCOM, session, EMAIL)).isInstanceOf(JourneyClosedException.class);

        assertThat(api.calls()).isEmpty();
    }

    // --- AC-05, ES-01, ES-02, EC-02 ---

    @ParameterizedTest
    @EnumSource(SystemTokenUnavailableException.Reason.class)
    @DisplayName("AC-05 — confirm_noSystemToken_isIssuanceUnavailableAndTheJourneyStaysInProgress")
    void confirm_noSystemToken_isIssuanceUnavailableAndTheJourneyStaysInProgress(
            SystemTokenUnavailableException.Reason reason) {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        SessionId session = entitledJourney();
        api.failWith(new SystemTokenUnavailableException(reason));

        assertThatThrownBy(() -> service.confirm(CGCOM, session, EMAIL)).satisfies(e -> assertProblem(e,
                ProblemCode.ISSUANCE_UNAVAILABLE));

        assertStillEntitled(session);
        assertThat(trail.issuanceRequests()).singleElement().satisfies(event -> {
            assertThat(event.result()).isEqualTo(JourneyAuditTrail.IssuanceOutcome.SYSTEM_TOKEN_UNAVAILABLE);
            assertThat(event.issuanceProcessId()).isEmpty();
        });
    }

    @ParameterizedTest
    @EnumSource(IssuanceRejectedException.Reason.class)
    @DisplayName("ES-01 — confirm_issuerRejects_isIssuanceRejectedAndTheJourneyStaysInProgress")
    void confirm_issuerRejects_isIssuanceRejectedAndTheJourneyStaysInProgress(IssuanceRejectedException.Reason reason) {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        SessionId session = entitledJourney();
        api.failWith(new IssuanceRejectedException(reason));

        assertThatThrownBy(() -> service.confirm(CGCOM, session, EMAIL)).satisfies(e -> assertProblem(e,
                ProblemCode.ISSUANCE_REJECTED));

        assertStillEntitled(session);
        assertThat(trail.issuanceRequests()).singleElement()
                .satisfies(event -> assertThat(event.result()).isEqualTo(JourneyAuditTrail.IssuanceOutcome.REJECTED));
    }

    @ParameterizedTest
    @EnumSource(IssuanceUnavailableException.Reason.class)
    @DisplayName("ES-02 — confirm_issuerDoesNotAnswer_isIssuanceUnavailableAndKeepsTheKey")
    void confirm_issuerDoesNotAnswer_isIssuanceUnavailableAndKeepsTheKey(IssuanceUnavailableException.Reason reason) {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        SessionId session = entitledJourney();
        api.failWith(new IssuanceUnavailableException(reason));

        assertThatThrownBy(() -> service.confirm(CGCOM, session, EMAIL)).satisfies(e -> assertProblem(e,
                ProblemCode.ISSUANCE_UNAVAILABLE));

        assertStillEntitled(session);
        IssuanceRecord kept = IssuanceRecord.fromJson(sessions.read(CGCOM, session, Section.ISSUANCE).orElseThrow());
        assertThat(kept.idempotencyKey()).isEqualTo(api.calls().getFirst().idempotencyKey());
        assertThat(kept.offerUri()).isEmpty();
        assertThat(trail.issuanceRequests()).singleElement()
                .satisfies(event -> assertThat(event.result()).isEqualTo(JourneyAuditTrail.IssuanceOutcome.UNAVAILABLE));
    }

    @Test
    @DisplayName("EC-02 — confirm_afterIssuanceUnavailable_reusesTheSameKeyAndEndsWithOneProcess")
    void confirm_afterIssuanceUnavailable_reusesTheSameKeyAndEndsWithOneProcess() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        SessionId session = entitledJourney();
        api.failWith(new IssuanceUnavailableException(IssuanceUnavailableException.Reason.NETWORK));
        assertThatThrownBy(() -> service.confirm(CGCOM, session, EMAIL)).isInstanceOf(ProblemException.class);
        api.recover();

        IssuanceService.Offer offer = service.confirm(CGCOM, session, EMAIL);

        assertThat(api.calls()).hasSize(2);
        assertThat(api.calls().get(1).idempotencyKey()).isEqualTo(api.calls().get(0).idempotencyKey());
        assertThat(api.processes()).isEqualTo(1);
        assertThat(offer.credentialOfferUri()).isNotBlank();
        assertThat(journeys.findById(CGCOM, journeyOf(session)).orElseThrow().state())
                .isEqualTo(JourneyState.COMPLETED);
    }

    @Test
    @DisplayName("EC-02 — confirm_retryWithAnotherEmail_sendsTheFirstEmailWithTheSameKey")
    void confirm_retryWithAnotherEmail_sendsTheFirstEmailWithTheSameKey() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        SessionId session = entitledJourney();
        api.failWith(new IssuanceUnavailableException(IssuanceUnavailableException.Reason.NETWORK));
        assertThatThrownBy(() -> service.confirm(CGCOM, session, EMAIL)).isInstanceOf(ProblemException.class);
        api.recover();

        service.confirm(CGCOM, session, "other.address@example.test");

        assertThat(api.calls()).extracting(c -> c.request().email()).containsExactly(EMAIL, EMAIL);
        assertThat(api.calls().get(1).idempotencyKey()).isEqualTo(api.calls().get(0).idempotencyKey());
        assertThat(EmailRecord.fromJson(sessions.read(CGCOM, session, Section.EMAIL).orElseThrow()).address())
                .isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("EC-01 — confirm_retryWithInvalidEmail_isStillRejectedAndKeepsTheFirstEmail")
    void confirm_retryWithInvalidEmail_isStillRejectedAndKeepsTheFirstEmail() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        SessionId session = entitledJourney();
        api.failWith(new IssuanceUnavailableException(IssuanceUnavailableException.Reason.NETWORK));
        assertThatThrownBy(() -> service.confirm(CGCOM, session, EMAIL)).isInstanceOf(ProblemException.class);

        assertThatThrownBy(() -> service.confirm(CGCOM, session, "nope")).satisfies(e -> assertProblem(e,
                ProblemCode.EMAIL_INVALID));

        assertThat(api.calls()).hasSize(1);
        assertThat(EmailRecord.fromJson(sessions.read(CGCOM, session, Section.EMAIL).orElseThrow()).address())
                .isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("EC-02 — confirm_differentJourneys_useDifferentKeys")
    void confirm_differentJourneys_useDifferentKeys() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));

        service.confirm(CGCOM, entitledJourney(), EMAIL);
        service.confirm(CGCOM, entitledJourney(), EMAIL);

        assertThat(api.calls().get(1).idempotencyKey()).isNotEqualTo(api.calls().get(0).idempotencyKey());
        assertThat(api.processes()).isEqualTo(2);
    }

    // --- EC-04 ---

    @Test
    @DisplayName("EC-04 — confirm_processAlreadyRecorded_isOnboardingUnavailableWithoutAskingTheIssuer")
    void confirm_processAlreadyRecorded_isOnboardingUnavailableWithoutAskingTheIssuer() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        SessionId session = entitledJourney();
        Journey stored = journeys.findById(CGCOM, journeyOf(session)).orElseThrow();
        UUID process = UUID.randomUUID();
        journeys.recordIssuanceProcess(CGCOM, stored.id(), process);

        assertThatThrownBy(() -> service.confirm(CGCOM, session, EMAIL)).satisfies(e -> assertProblem(e,
                ProblemCode.ONBOARDING_UNAVAILABLE));

        assertThat(api.calls()).isEmpty();
        Journey after = journeys.findById(CGCOM, stored.id()).orElseThrow();
        assertThat(after.state()).isEqualTo(JourneyState.IN_PROGRESS);
        assertThat(after.issuanceProcessId()).contains(process);
    }

    // --- EC-03, NFR-2: what the second transaction does when the journey changed during the Issuer call ---

    @Test
    @DisplayName("EC-03 — confirm_parallelConfirmationCompletesFirst_answersWithItsOfferAndOneProcess")
    void confirm_parallelConfirmationCompletesFirst_answersWithItsOfferAndOneProcess() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        SessionId session = entitledJourney();
        AtomicReference<IssuanceService.Offer> parallel = new AtomicReference<>();
        AtomicBoolean confirmedAgain = new AtomicBoolean();
        issuer = (tenant, key, request) -> {
            if (confirmedAgain.compareAndSet(false, true)) {
                // The same holder confirms again while the first request is waiting for the Issuer.
                parallel.set(service.confirm(tenant, session, EMAIL));
            }
            return api.issue(tenant, key, request);
        };

        IssuanceService.Offer offer = service.confirm(CGCOM, session, EMAIL);

        assertThat(offer).isEqualTo(parallel.get());
        assertThat(api.calls()).hasSize(2);
        assertThat(api.calls().get(1).idempotencyKey()).isEqualTo(api.calls().get(0).idempotencyKey());
        assertThat(api.processes()).isEqualTo(1);
        Journey stored = journeys.findById(CGCOM, journeyOf(session)).orElseThrow();
        assertThat(stored.state()).isEqualTo(JourneyState.COMPLETED);
        assertThat(trail.events().stream().filter(e -> e.kind() == JourneyAuditTrail.Kind.COMPLETED)).hasSize(1);
    }

    @Test
    @DisplayName("NFR-2 — confirm_issuerAnswersAnotherProcessForAJourneyThatHasOne_keepsTheStoredOne")
    void confirm_issuerAnswersAnotherProcessForAJourneyThatHasOne_keepsTheStoredOne() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        SessionId session = entitledJourney();
        UUID stored = UUID.randomUUID();
        issuer = (tenant, key, request) -> {
            journeys.recordIssuanceProcess(tenant, journeyOf(session), stored);
            return api.issue(tenant, key, request);
        };

        assertThatThrownBy(() -> service.confirm(CGCOM, session, EMAIL)).satisfies(e -> assertProblem(e,
                ProblemCode.ONBOARDING_UNAVAILABLE));

        Journey after = journeys.findById(CGCOM, journeyOf(session)).orElseThrow();
        assertThat(after.issuanceProcessId()).contains(stored);
        assertThat(after.state()).isEqualTo(JourneyState.IN_PROGRESS);
        assertThat(IssuanceRecord.fromJson(sessions.read(CGCOM, session, Section.ISSUANCE).orElseThrow()).offerUri())
                .isEmpty();
    }

    @Test
    void confirm_journeyWithdrawnWhileTheIssuerWasAsked_isJourneyClosed() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        SessionId session = entitledJourney();
        issuer = (tenant, key, request) -> {
            Journey current = journeys.findById(tenant, journeyOf(session)).orElseThrow();
            current.withdraw(WithdrawalReason.EXPIRED, clock.instant());
            journeys.update(tenant, current, JourneyState.IN_PROGRESS);
            return api.issue(tenant, key, request);
        };

        assertThatThrownBy(() -> service.confirm(CGCOM, session, EMAIL)).satisfies(e -> assertProblem(e,
                ProblemCode.JOURNEY_CLOSED));

        assertThat(journeys.findById(CGCOM, journeyOf(session)).orElseThrow().issuanceProcessId()).isEmpty();
    }

    // --- AC-03 (ticket), AC-10, AC-11, AC-12 ---

    private static final String WALLET_UNIT_SERVICE = "https://cgcom.wallet.test/business-wallet/api/v1";

    private void withOnboarding() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, new Onboarding(true, WALLET_UNIT_SERVICE)));
    }

    @Test
    @DisplayName("AC-03 — confirm_tenantWithOnboarding_requestsTheTicketWithTheOfferAsIsAndAnswersTheAddress")
    void confirm_tenantWithOnboarding_requestsTheTicketWithTheOfferAsIsAndAnswersTheAddress() {
        withOnboarding();
        SessionId session = entitledJourney();

        IssuanceService.Offer offer = service.confirm(CGCOM, session, EMAIL);

        assertThat(tickets.calls()).singleElement().satisfies(call -> {
            assertThat(call.tenant()).isEqualTo(CGCOM);
            assertThat(call.walletUnitServiceUrl()).isEqualTo(WALLET_UNIT_SERVICE);
            assertThat(call.email()).isEqualTo(EMAIL);
            assertThat(call.credentialOffer()).isEqualTo(offer.credentialOfferUri());
        });
        assertThat(offer.onboardingUrl()).isEqualTo("https://cgcom.wallet.test/t/ticket-1");
        Journey stored = journeys.findById(CGCOM, journeyOf(session)).orElseThrow();
        assertThat(stored.state()).isEqualTo(JourneyState.COMPLETED);
        assertThat(stored.step()).isEqualTo(JourneyStep.OFFER_OBTAINED);
        IssuanceRecord issuance = IssuanceRecord.fromJson(sessions.read(CGCOM, session, Section.ISSUANCE).orElseThrow());
        assertThat(issuance.credentialOfferUri()).isEqualTo(offer.credentialOfferUri());
        assertThat(issuance.offerExpiresAt()).isEqualTo(offer.expiresAt());
        assertThat(issuance.onboarding()).contains(offer.onboardingUrl());
        assertThat(issuance.onboardingExpiresAt()).isEqualTo(offer.onboardingExpiresAt());
        assertThat(offer.toString()).doesNotContain("openid-credential-offer", "ticket-1");
        assertThat(ticketAskedInsideTransaction).isFalse();
    }

    @Test
    @DisplayName("AC-03 — confirm_tenantWithOnboarding_tracesTheTicketRequestAndTheIssuanceWithoutPersonalData")
    void confirm_tenantWithOnboarding_tracesTheTicketRequestAndTheIssuanceWithoutPersonalData() {
        withOnboarding();
        SessionId session = entitledJourney();

        IssuanceService.Offer offer = service.confirm(CGCOM, session, EMAIL);

        assertThat(trail.ticketRequests()).singleElement().satisfies(event -> {
            assertThat(event.result()).isEqualTo(JourneyAuditTrail.TicketOutcome.CREATED);
            assertThat(event.journeyId()).isEqualTo(journeyOf(session));
            assertThat(event.toString()).doesNotContain(EMAIL, "openid-credential-offer", offer.onboardingUrl());
        });
        assertThat(trail.issuanceRequests()).singleElement()
                .satisfies(event -> assertThat(event.result()).isEqualTo(JourneyAuditTrail.IssuanceOutcome.ISSUED));
    }

    @Test
    @DisplayName("AC-11 — confirm_tenantWithOnboarding_asksATicketThatExpiresSixtySecondsBeforeTheOffer")
    void confirm_tenantWithOnboarding_asksATicketThatExpiresSixtySecondsBeforeTheOffer() {
        withOnboarding();
        SessionId session = entitledJourney();

        IssuanceService.Offer offer = service.confirm(CGCOM, session, EMAIL);

        assertThat(tickets.calls().getFirst().expiresIn())
                .isEqualTo(Duration.between(clock.instant(), offer.expiresAt()).minusSeconds(60))
                .isEqualTo(Duration.ofSeconds(540));
        assertThat(offer.onboardingExpiresAt()).isBefore(offer.expiresAt());
    }

    @Test
    @DisplayName("AC-10 — confirm_tenantWithoutOnboarding_doesNotCallTheWalletUnitServiceAndAnswersOnlyTheOffer")
    void confirm_tenantWithoutOnboarding_doesNotCallTheWalletUnitServiceAndAnswersOnlyTheOffer() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        SessionId session = entitledJourney();

        IssuanceService.Offer offer = service.confirm(CGCOM, session, EMAIL);

        assertThat(tickets.calls()).isEmpty();
        assertThat(trail.ticketRequests()).isEmpty();
        assertThat(offer.onboardingUrl()).isNull();
        assertThat(offer.onboardingExpiresAt()).isNull();
        assertThat(journeys.findById(CGCOM, journeyOf(session)).orElseThrow().state()).isEqualTo(JourneyState.COMPLETED);
    }

    @ParameterizedTest
    @EnumSource(OnboardingTicketException.Reason.class)
    @DisplayName("AC-12 — confirm_ticketNotCreated_isOnboardingUnavailableWithoutOfferAndKeepsOnlyTheProcess")
    void confirm_ticketNotCreated_isOnboardingUnavailableWithoutOfferAndKeepsOnlyTheProcess(
            OnboardingTicketException.Reason reason) {
        withOnboarding();
        SessionId session = entitledJourney();
        tickets.failWith(reason);

        assertThatThrownBy(() -> service.confirm(CGCOM, session, EMAIL)).satisfies(e -> assertProblem(e,
                ProblemCode.ONBOARDING_UNAVAILABLE));

        Journey stored = journeys.findById(CGCOM, journeyOf(session)).orElseThrow();
        assertThat(stored.state()).isEqualTo(JourneyState.IN_PROGRESS);
        assertThat(stored.step()).isEqualTo(JourneyStep.ENTITLEMENT_CONFIRMED);
        assertThat(stored.issuanceProcessId()).isPresent();
        IssuanceRecord issuance = IssuanceRecord.fromJson(sessions.read(CGCOM, session, Section.ISSUANCE).orElseThrow());
        assertThat(issuance.offerUri()).isEmpty();
        assertThat(issuance.offerExpiresAt()).isNull();
        assertThat(issuance.onboarding()).isEmpty();
        assertThat(issuance.onboardingExpiresAt()).isNull();
        assertThat(trail.issuanceRequests()).singleElement().satisfies(event -> {
            assertThat(event.result()).isEqualTo(JourneyAuditTrail.IssuanceOutcome.ONBOARDING_FAILED);
            assertThat(event.issuanceProcessId()).contains(stored.issuanceProcessId().orElseThrow());
        });
        assertThat(trail.ticketRequests()).singleElement()
                .satisfies(event -> assertThat(event.result()).isEqualTo(JourneyAuditTrail.TicketOutcome.FAILED));
        assertThat(trail.events().stream().filter(e -> e.kind() == JourneyAuditTrail.Kind.COMPLETED)).isEmpty();
    }

    @Test
    @DisplayName("AC-12 — confirm_afterTheTicketFailed_isOnboardingUnavailableWithoutAskingAnySystemAgain")
    void confirm_afterTheTicketFailed_isOnboardingUnavailableWithoutAskingAnySystemAgain() {
        withOnboarding();
        SessionId session = entitledJourney();
        tickets.failWith(OnboardingTicketException.Reason.UNAVAILABLE);
        assertThatThrownBy(() -> service.confirm(CGCOM, session, EMAIL)).satisfies(e -> assertProblem(e,
                ProblemCode.ONBOARDING_UNAVAILABLE));
        tickets.recover();

        assertThatThrownBy(() -> service.confirm(CGCOM, session, EMAIL)).satisfies(e -> assertProblem(e,
                ProblemCode.ONBOARDING_UNAVAILABLE));

        assertThat(api.calls()).hasSize(1);
        assertThat(tickets.calls()).hasSize(1);
    }

    @Test
    @DisplayName("AC-11 — confirm_offerWithTooLittleLife_requestsNoTicketAndIsOnboardingUnavailable")
    void confirm_offerWithTooLittleLife_requestsNoTicketAndIsOnboardingUnavailable() {
        withOnboarding();
        SessionId session = entitledJourney();
        issuer = (tenant, key, request) -> new IssuanceResult(UUID.randomUUID(), "openid-credential-offer://?x=1",
                clock.instant().plusSeconds(60));

        assertThatThrownBy(() -> service.confirm(CGCOM, session, EMAIL)).satisfies(e -> assertProblem(e,
                ProblemCode.ONBOARDING_UNAVAILABLE));

        assertThat(tickets.calls()).isEmpty();
        assertThat(trail.ticketRequests()).singleElement()
                .satisfies(event -> assertThat(event.result()).isEqualTo(JourneyAuditTrail.TicketOutcome.FAILED));
        assertThat(trail.issuanceRequests()).singleElement().satisfies(event ->
                assertThat(event.result()).isEqualTo(JourneyAuditTrail.IssuanceOutcome.ONBOARDING_FAILED));
        assertThat(journeys.findById(CGCOM, journeyOf(session)).orElseThrow().state())
                .isEqualTo(JourneyState.IN_PROGRESS);
    }

    @Test
    void confirm_ticketFailsAndAuditTrailFails_stillAnswersOnboardingUnavailable() {
        withOnboarding();
        SessionId session = entitledJourney();
        tickets.failWith(OnboardingTicketException.Reason.REJECTED);
        trail.failFromNowOn();

        assertThatThrownBy(() -> service.confirm(CGCOM, session, EMAIL)).satisfies(e -> assertProblem(e,
                ProblemCode.ONBOARDING_UNAVAILABLE));
    }

    // --- helpers ---

    private void assertProblem(Throwable thrown, ProblemCode expected) {
        assertThat(thrown).isInstanceOfSatisfying(ProblemException.class,
                e -> assertThat(e.problem()).isEqualTo(expected));
    }

    private void assertNothingAsked(SessionId session) {
        assertThat(api.calls()).isEmpty();
        assertThat(sessions.read(CGCOM, session, Section.ISSUANCE)).isEmpty();
        assertThat(sessions.read(CGCOM, session, Section.EMAIL)).isEmpty();
        Journey stored = journeys.findById(CGCOM, journeyOf(session)).orElseThrow();
        assertThat(stored.state()).isEqualTo(JourneyState.IN_PROGRESS);
        assertThat(stored.issuanceProcessId()).isEmpty();
    }

    /** ES-01, ES-02, AC-05: the journey is where the entitlement left it, without a process or an offer. */
    private void assertStillEntitled(SessionId session) {
        Journey stored = journeys.findById(CGCOM, journeyOf(session)).orElseThrow();
        assertThat(stored.state()).isEqualTo(JourneyState.IN_PROGRESS);
        assertThat(stored.step()).isEqualTo(JourneyStep.ENTITLEMENT_CONFIRMED);
        assertThat(stored.issuanceProcessId()).isEmpty();
        assertThat(sessions.read(CGCOM, session, Section.ENTITLEMENT)).isPresent();
    }

    private UUID journeyOf(SessionId session) {
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
        sessions.write(CGCOM, session, Section.ENTITLEMENT, entitlement().toJson());
        journey.reach(CGCOM, session, JourneyProgress.Step.ENTITLEMENT_CONFIRMED);
        return session;
    }

    private EntitlementRecord entitlement() {
        return new EntitlementRecord(IdentifierType.DNI, CLAIMS,
                new EntitlementRecord.Preview("DoctorID", List.of(), null, true), clock.instant());
    }

    private static PortalConfig config(String attestationType, Map<String, String> placement, Onboarding onboarding) {
        return new PortalConfig(Map.of(), new LegalLinks(null, null, null, null),
                new Support(null, null, null, null), "https://cgcom.test/wallet",
                new ServiceSettings(attestationType, placement, onboarding, null, null));
    }
}
