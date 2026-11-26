package es.in2.issuanceportal.journey.application;

import es.in2.issuanceportal.MutableClock;
import es.in2.issuanceportal.journey.InMemoryIssuanceApi;
import es.in2.issuanceportal.journey.InMemoryJourneyIssuanceRepository;
import es.in2.issuanceportal.journey.InMemoryJourneyRepository;
import es.in2.issuanceportal.journey.InMemoryOnboardingTickets;
import es.in2.issuanceportal.journey.RecordingJourneyAuditTrail;
import es.in2.issuanceportal.journey.api.JourneyProgress;
import es.in2.issuanceportal.journey.application.port.IssuanceApi;
import es.in2.issuanceportal.journey.application.port.IssuanceRejectedException;
import es.in2.issuanceportal.journey.application.port.IssuanceRequest;
import es.in2.issuanceportal.journey.application.port.IssuanceUnavailableException;
import es.in2.issuanceportal.journey.application.port.IssuanceResult;
import es.in2.issuanceportal.journey.application.port.JourneyAuditTrail;
import es.in2.issuanceportal.journey.application.port.OnboardingTicketException;
import es.in2.issuanceportal.shared.systemtoken.SystemTokenUnavailableException;
import es.in2.issuanceportal.journey.domain.Journey;
import es.in2.issuanceportal.journey.domain.JourneyIssuance;
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
import es.in2.issuanceportal.session.api.EmailVerificationRecord;
import es.in2.issuanceportal.session.EntitlementRecords;
import es.in2.issuanceportal.session.api.EntitlementRecord;
import es.in2.issuanceportal.session.api.HolderIdentity;
import es.in2.issuanceportal.session.api.IdentifierType;
import es.in2.issuanceportal.session.api.IssuanceRecord;
import es.in2.issuanceportal.session.api.PersonalIdentifier;
import es.in2.issuanceportal.session.api.Section;
import es.in2.issuanceportal.session.Holders;
import es.in2.issuanceportal.session.api.HolderRef;
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
    private final InMemoryJourneyIssuanceRepository issuances = new InMemoryJourneyIssuanceRepository();
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
    /** What the Issuer does when it is asked for an issuance; a test replaces it. */
    private interface Issue {
        IssuanceResult issue(TenantId tenant, UUID key, IssuanceRequest request);
    }

    private Issue issuer = api::issue;
    private final IssuanceService service = new IssuanceService(journey, journeys, issuances, sessions,
            new PortalConfigService(tenant -> Optional.ofNullable(configs.get(tenant))),
            new IssuanceApi() {
                @Override
                public IssuanceResult issue(TenantId tenant, UUID key, IssuanceRequest request) {
                    askedInsideTransaction.compareAndSet(false, inTransaction.get());
                    return issuer.issue(tenant, key, request);
                }

                @Override
                public IssuanceResult renewOffer(TenantId tenant, UUID processId) {
                    askedInsideTransaction.compareAndSet(false, inTransaction.get());
                    return api.renewOffer(tenant, processId);
                }
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
        HolderRef holder = entitledJourney();

        IssuanceService.Offer offer = service.confirm(CGCOM, holder, "r1");

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
        Journey stored = journeys.findById(CGCOM, journeyOf(holder)).orElseThrow();
        assertThat(stored.state()).isEqualTo(JourneyState.COMPLETED);
        assertThat(stored.step()).isEqualTo(JourneyStep.OFFER_OBTAINED);
        assertThat(processOf(stored.id())).isPresent();
        assertThat(offer.credentialOfferUri()).startsWith("openid-credential-offer://");
        assertThat(offer.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(10)));
        assertThat(offer.toString()).doesNotContain("openid-credential-offer");
    }

    @Test
    @DisplayName("AC-03 — confirm_entitledHolder_keepsKeyOfferAndEmailInTheSession")
    void confirm_entitledHolder_keepsKeyOfferAndEmailInTheSession() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        HolderRef holder = entitledJourney();

        IssuanceService.Offer offer = service.confirm(CGCOM, holder, "r1");

        IssuanceRecord.Entry issuance = IssuanceRecord.fromJson(sessions.read(CGCOM, journeyOf(holder), Section.ISSUANCE).orElseThrow()).entry("r1").orElseThrow();
        assertThat(issuance.idempotencyKey()).isEqualTo(api.calls().getFirst().idempotencyKey());
        assertThat(issuance.credentialOfferUri()).isEqualTo(offer.credentialOfferUri());
        assertThat(issuance.offerExpiresAt()).isEqualTo(offer.expiresAt());
        assertThat(issuance.onboarding()).isEmpty();
        assertThat(EmailRecord.fromJson(sessions.read(CGCOM, journeyOf(holder), Section.EMAIL).orElseThrow()).address())
                .isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("AC-03 — confirm_entitledHolder_tracesTheRequestAndTheCompletionWithoutPersonalData")
    void confirm_entitledHolder_tracesTheRequestAndTheCompletionWithoutPersonalData() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        HolderRef holder = entitledJourney();

        service.confirm(CGCOM, holder, "r1");

        assertThat(trail.issuanceRequests()).singleElement().satisfies(event -> {
            assertThat(event.result()).isEqualTo(JourneyAuditTrail.IssuanceOutcome.ISSUED);
            assertThat(event.journeyId()).isEqualTo(journeyOf(holder));
            assertThat(event.issuanceProcessId()).isPresent();
            assertThat(event.toString()).doesNotContain(EMAIL, "openid-credential-offer");
        });
        assertThat(trail.events().getLast().kind()).isEqualTo(JourneyAuditTrail.Kind.COMPLETED);
        assertThat(trail.events().getLast().step()).isEqualTo(JourneyStep.OFFER_OBTAINED);
    }

    @Test
    void confirm_auditTrailFails_failsTheConfirmation() {
        // Arrange
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        HolderRef holder = entitledJourney();
        trail.failFromNowOn();

        // Act & Assert
        assertThatThrownBy(() -> service.confirm(CGCOM, holder, "r1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void confirm_entitledHolder_doesNotAskTheIssuerInsideATransaction() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        HolderRef holder = entitledJourney();

        service.confirm(CGCOM, holder, "r1");

        assertThat(api.calls()).hasSize(1);
        assertThat(askedInsideTransaction).isFalse();
    }

    // --- AC-01 ---

    @Test
    @DisplayName("AC-01 — confirm_noEntitlement_isStepNotAllowedAndNothingChanges")
    void confirm_noEntitlement_isStepNotAllowedAndNothingChanges() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        HolderRef holder = identifiedJourney();
        Journey before = journeys.findById(CGCOM, journeyOf(holder)).orElseThrow();

        assertThatThrownBy(() -> service.confirm(CGCOM, holder, "r1")).satisfies(e -> assertProblem(e,
                ProblemCode.STEP_NOT_ALLOWED));

        assertThat(api.calls()).isEmpty();
        assertThat(sessions.read(CGCOM, journeyOf(holder), Section.ISSUANCE)).isEmpty();
        assertThat(sessions.read(CGCOM, journeyOf(holder), Section.EMAIL)).isEmpty();
        Journey after = journeys.findById(CGCOM, before.id()).orElseThrow();
        assertThat(after.state()).isEqualTo(JourneyState.IN_PROGRESS);
        assertThat(after.step()).isEqualTo(JourneyStep.IDENTIFIED);
        assertThat(processOf(after.id())).isEmpty();
    }

    @Test
    @DisplayName("AC-01 — confirm_noIdentity_isStepNotAllowed")
    void confirm_noIdentity_isStepNotAllowed() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        HolderRef holder = startJourney(CGCOM);
        sessions.write(CGCOM, journeyOf(holder), Section.ENTITLEMENT, entitlement().toJson());

        assertThatThrownBy(() -> service.confirm(CGCOM, holder, "r1")).satisfies(e -> assertProblem(e,
                ProblemCode.STEP_NOT_ALLOWED));

        assertThat(api.calls()).isEmpty();
    }

    // --- EC-01 ---

    @Test
    @DisplayName("AC-08 — confirm_noVerifiedEmail_isStepNotAllowedAndNoSystemIsCalled")
    void confirm_noVerifiedEmail_isStepNotAllowedAndNoSystemIsCalled() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        HolderRef holder = entitledJourneyWithoutEmail();

        assertThatThrownBy(() -> service.confirm(CGCOM, holder, "r1")).satisfies(e -> assertProblem(e,
                ProblemCode.STEP_NOT_ALLOWED));

        assertThat(api.calls()).isEmpty();
        assertThat(tickets.calls()).isEmpty();
        assertThat(sessions.read(CGCOM, journeyOf(holder), Section.ISSUANCE)).isEmpty();
        assertThat(sessions.read(CGCOM, journeyOf(holder), Section.EMAIL)).isEmpty();
        Journey stored = journeys.findById(CGCOM, journeyOf(holder)).orElseThrow();
        assertThat(stored.state()).isEqualTo(JourneyState.IN_PROGRESS);
        assertThat(stored.step()).isEqualTo(JourneyStep.ENTITLEMENT_CONFIRMED);
        assertThat(processOf(stored.id())).isEmpty();
    }

    @Test
    @DisplayName("AC-08 — confirm_emailSectionInvalid_isStepNotAllowedAndNoSystemIsCalled")
    void confirm_emailSectionInvalid_isStepNotAllowedAndNoSystemIsCalled() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        HolderRef holder = entitledJourneyWithoutEmail();
        sessions.write(CGCOM, journeyOf(holder), Section.EMAIL, "{}");

        assertThatThrownBy(() -> service.confirm(CGCOM, holder, "r1")).satisfies(e -> assertProblem(e,
                ProblemCode.STEP_NOT_ALLOWED));

        assertThat(api.calls()).isEmpty();
    }

    // --- ES-04 ---

    @Test
    @DisplayName("ES-04 — confirm_incompleteConfiguration_isConfigUnavailableAndNoSystemIsCalled")
    void confirm_incompleteConfiguration_isConfigUnavailableAndNoSystemIsCalled() {
        HolderRef holder = entitledJourney();
        List<PortalConfig> incomplete = List.of(
                config(null, DOCTORID_PLACEMENT, Onboarding.DISABLED),
                config(" ", DOCTORID_PLACEMENT, Onboarding.DISABLED),
                config("doctorid.sd.1", Map.of(), Onboarding.DISABLED),
                config("doctorid.sd.1", DOCTORID_PLACEMENT, new Onboarding(true, null)));

        for (PortalConfig config : incomplete) {
            configs.put(CGCOM, config);
            assertThatThrownBy(() -> service.confirm(CGCOM, holder, "r1")).satisfies(e -> assertProblem(e,
                    ProblemCode.CONFIG_UNAVAILABLE));
        }

        assertNothingAsked(holder);
    }

    @Test
    @DisplayName("ES-04 — confirm_tenantWithoutConfiguration_isConfigUnavailable")
    void confirm_tenantWithoutConfiguration_isConfigUnavailable() {
        HolderRef holder = entitledJourney();

        assertThatThrownBy(() -> service.confirm(CGCOM, holder, "r1")).satisfies(e -> assertProblem(e,
                ProblemCode.CONFIG_UNAVAILABLE));

        assertNothingAsked(holder);
    }

    @Test
    @DisplayName("ES-04 — confirm_placementPointsAtDataTheJourneyDoesNotHave_isConfigUnavailable")
    void confirm_placementPointsAtDataTheJourneyDoesNotHave_isConfigUnavailable() {
        configs.put(CGCOM, config("doctorid.sd.1", Map.of("givenName", "identity.given_name",
                "unknown", "authentic_source.not_translated"), Onboarding.DISABLED));
        HolderRef holder = entitledJourney();

        assertThatThrownBy(() -> service.confirm(CGCOM, holder, "r1")).satisfies(e -> assertProblem(e,
                ProblemCode.CONFIG_UNAVAILABLE));

        assertNothingAsked(holder);
    }

    // --- EC-09, ES-06 ---

    @Test
    @DisplayName("ES-06 — confirm_requestWhoseAddressHasNotExpired_isStepNotAllowedAndTheOfferKeepsServing")
    void confirm_requestWhoseAddressHasNotExpired_isStepNotAllowedAndTheOfferKeepsServing() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        HolderRef holder = entitledJourney();
        service.confirm(CGCOM, holder, "r1");

        // The journey is completed and active: not closed. The address delivered still works, so no new offer.
        assertThatThrownBy(() -> service.confirm(CGCOM, holder, "r1")).satisfies(e -> assertProblem(e,
                ProblemCode.STEP_NOT_ALLOWED));

        assertThat(api.calls()).hasSize(1);
        assertThat(api.renewals()).isEmpty();
    }

    @Test
    @DisplayName("ES-06 — confirm_sessionWithoutJourney_isJourneyClosed")
    void confirm_sessionWithoutJourney_isJourneyClosed() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));

        HolderRef unknown = Holders.next();

        assertThatThrownBy(() -> service.confirm(CGCOM, unknown, "r1"))
                .isInstanceOf(JourneyClosedException.class);

        assertThat(api.calls()).isEmpty();
    }

    @Test
    void confirm_withdrawnJourney_isJourneyClosed() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        HolderRef holder = entitledJourney();
        journey.cancel(CGCOM, holder);

        assertThatThrownBy(() -> service.confirm(CGCOM, holder, "r1")).isInstanceOf(JourneyClosedException.class);

        assertThat(api.calls()).isEmpty();
    }

    // --- AC-05, ES-01, ES-02, EC-02 ---

    @ParameterizedTest
    @EnumSource(SystemTokenUnavailableException.Reason.class)
    @DisplayName("AC-05 — confirm_noSystemToken_isIssuanceUnavailableAndTheJourneyStaysInProgress")
    void confirm_noSystemToken_isIssuanceUnavailableAndTheJourneyStaysInProgress(
            SystemTokenUnavailableException.Reason reason) {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        HolderRef holder = entitledJourney();
        api.failWith(new SystemTokenUnavailableException(reason));

        assertThatThrownBy(() -> service.confirm(CGCOM, holder, "r1")).satisfies(e -> assertProblem(e,
                ProblemCode.ISSUANCE_UNAVAILABLE));

        assertStillEntitled(holder);
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
        HolderRef holder = entitledJourney();
        api.failWith(new IssuanceRejectedException(reason));

        assertThatThrownBy(() -> service.confirm(CGCOM, holder, "r1")).satisfies(e -> assertProblem(e,
                ProblemCode.ISSUANCE_REJECTED));

        assertStillEntitled(holder);
        assertThat(trail.issuanceRequests()).singleElement()
                .satisfies(event -> assertThat(event.result()).isEqualTo(JourneyAuditTrail.IssuanceOutcome.REJECTED));
    }

    @ParameterizedTest
    @EnumSource(IssuanceUnavailableException.Reason.class)
    @DisplayName("ES-02 — confirm_issuerDoesNotAnswer_isIssuanceUnavailableAndKeepsTheKey")
    void confirm_issuerDoesNotAnswer_isIssuanceUnavailableAndKeepsTheKey(IssuanceUnavailableException.Reason reason) {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        HolderRef holder = entitledJourney();
        api.failWith(new IssuanceUnavailableException(reason));

        assertThatThrownBy(() -> service.confirm(CGCOM, holder, "r1")).satisfies(e -> assertProblem(e,
                ProblemCode.ISSUANCE_UNAVAILABLE));

        assertStillEntitled(holder);
        IssuanceRecord.Entry kept = IssuanceRecord.fromJson(sessions.read(CGCOM, journeyOf(holder), Section.ISSUANCE).orElseThrow()).entry("r1").orElseThrow();
        assertThat(kept.idempotencyKey()).isEqualTo(api.calls().getFirst().idempotencyKey());
        assertThat(kept.offerUri()).isEmpty();
        assertThat(trail.issuanceRequests()).singleElement()
                .satisfies(event -> assertThat(event.result()).isEqualTo(JourneyAuditTrail.IssuanceOutcome.UNAVAILABLE));
    }

    @Test
    @DisplayName("EC-02 — confirm_afterIssuanceUnavailable_reusesTheSameKeyAndEndsWithOneProcess")
    void confirm_afterIssuanceUnavailable_reusesTheSameKeyAndEndsWithOneProcess() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        HolderRef holder = entitledJourney();
        api.failWith(new IssuanceUnavailableException(IssuanceUnavailableException.Reason.NETWORK));
        assertThatThrownBy(() -> service.confirm(CGCOM, holder, "r1")).isInstanceOf(ProblemException.class);
        api.recover();

        IssuanceService.Offer offer = service.confirm(CGCOM, holder, "r1");

        assertThat(api.calls()).hasSize(2);
        assertThat(api.calls().get(1).idempotencyKey()).isEqualTo(api.calls().get(0).idempotencyKey());
        assertThat(api.processes()).isEqualTo(1);
        assertThat(offer.credentialOfferUri()).isNotBlank();
        assertThat(journeys.findById(CGCOM, journeyOf(holder)).orElseThrow().state())
                .isEqualTo(JourneyState.COMPLETED);
    }

    @Test
    @DisplayName("EC-05 — confirm_retryAfterAFailure_sendsTheVerifiedEmailAgainWithTheSameKey")
    void confirm_retryAfterAFailure_sendsTheVerifiedEmailAgainWithTheSameKey() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        HolderRef holder = entitledJourney();
        api.failWith(new IssuanceUnavailableException(IssuanceUnavailableException.Reason.NETWORK));
        assertThatThrownBy(() -> service.confirm(CGCOM, holder, "r1")).isInstanceOf(ProblemException.class);
        api.recover();

        service.confirm(CGCOM, holder, "r1");

        assertThat(api.calls()).extracting(c -> c.request().email()).containsExactly(EMAIL, EMAIL);
        assertThat(api.calls().get(1).idempotencyKey()).isEqualTo(api.calls().get(0).idempotencyKey());
        assertThat(EmailRecord.fromJson(sessions.read(CGCOM, journeyOf(holder), Section.EMAIL).orElseThrow()).address())
                .isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("AC-07 — confirm_retryAfterANewCodeWasRequested_isStepNotAllowedUntilItIsChecked")
    void confirm_retryAfterANewCodeWasRequested_isStepNotAllowedUntilItIsChecked() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        HolderRef holder = entitledJourney();
        api.failWith(new IssuanceUnavailableException(IssuanceUnavailableException.Reason.NETWORK));
        assertThatThrownBy(() -> service.confirm(CGCOM, holder, "r1")).isInstanceOf(ProblemException.class);
        // What sending a new code does to the verified email.
        sessions.clear(CGCOM, journeyOf(holder), Section.EMAIL);

        assertThatThrownBy(() -> service.confirm(CGCOM, holder, "r1")).satisfies(e -> assertProblem(e,
                ProblemCode.STEP_NOT_ALLOWED));

        assertThat(api.calls()).hasSize(1);
    }

    @Test
    @DisplayName("EC-02 — confirm_differentJourneys_useDifferentKeys")
    void confirm_differentJourneys_useDifferentKeys() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));

        service.confirm(CGCOM, entitledJourney(), "r1");
        service.confirm(CGCOM, entitledJourney(), "r1");

        assertThat(api.calls().get(1).idempotencyKey()).isNotEqualTo(api.calls().get(0).idempotencyKey());
        assertThat(api.processes()).isEqualTo(2);
    }

    // --- EC-04 ---

    @Test
    @DisplayName("EC-04 — confirm_processAlreadyRecorded_renewsItsOfferWithoutAskingForAnotherIssuance")
    void confirm_processAlreadyRecorded_renewsItsOfferWithoutAskingForAnotherIssuance() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        HolderRef holder = entitledJourney();
        Journey stored = journeys.findById(CGCOM, journeyOf(holder)).orElseThrow();
        UUID process = UUID.randomUUID();
        issuances.insert(CGCOM, new JourneyIssuance(stored.id(), "r1", process, clock.instant(), null));

        IssuanceService.Offer offer = service.confirm(CGCOM, holder, "r1");

        assertThat(api.calls()).isEmpty();
        assertThat(api.renewals()).containsExactly(process);
        assertThat(offer.credentialOfferUri()).contains("renewed");
        Journey after = journeys.findById(CGCOM, stored.id()).orElseThrow();
        // The process had no offer yet, so this is the journey's first: it completes.
        assertThat(after.state()).isEqualTo(JourneyState.COMPLETED);
        assertThat(processOf(after.id())).contains(process);
    }

    // --- EC-03, NFR-2: what the second transaction does when the journey changed during the Issuer call ---

    @Test
    @DisplayName("EC-03 — confirm_parallelConfirmationCompletesFirst_answersWithItsOfferAndOneProcess")
    void confirm_parallelConfirmationCompletesFirst_answersWithItsOfferAndOneProcess() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        HolderRef holder = entitledJourney();
        AtomicReference<IssuanceService.Offer> parallel = new AtomicReference<>();
        AtomicBoolean confirmedAgain = new AtomicBoolean();
        issuer = (tenant, key, request) -> {
            if (confirmedAgain.compareAndSet(false, true)) {
                // The same holder confirms again while the first request is waiting for the Issuer.
                parallel.set(service.confirm(tenant, holder, "r1"));
            }
            return api.issue(tenant, key, request);
        };

        IssuanceService.Offer offer = service.confirm(CGCOM, holder, "r1");

        assertThat(offer).isEqualTo(parallel.get());
        assertThat(api.calls()).hasSize(2);
        assertThat(api.calls().get(1).idempotencyKey()).isEqualTo(api.calls().get(0).idempotencyKey());
        assertThat(api.processes()).isEqualTo(1);
        Journey stored = journeys.findById(CGCOM, journeyOf(holder)).orElseThrow();
        assertThat(stored.state()).isEqualTo(JourneyState.COMPLETED);
        assertThat(trail.events().stream().filter(e -> e.kind() == JourneyAuditTrail.Kind.COMPLETED)).hasSize(1);
    }

    @Test
    @DisplayName("NFR-2 — confirm_issuerAnswersAnotherProcessForAJourneyThatHasOne_keepsTheStoredOne")
    void confirm_issuerAnswersAnotherProcessForAJourneyThatHasOne_keepsTheStoredOne() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        HolderRef holder = entitledJourney();
        UUID stored = UUID.randomUUID();
        issuer = (tenant, key, request) -> {
            issuances.insert(tenant, new JourneyIssuance(journeyOf(holder), "r1", stored, clock.instant(), null));
            return api.issue(tenant, key, request);
        };

        assertThatThrownBy(() -> service.confirm(CGCOM, holder, "r1")).satisfies(e -> assertProblem(e,
                ProblemCode.ONBOARDING_UNAVAILABLE));

        Journey after = journeys.findById(CGCOM, journeyOf(holder)).orElseThrow();
        assertThat(processOf(after.id())).contains(stored);
        assertThat(after.state()).isEqualTo(JourneyState.IN_PROGRESS);
        assertThat(IssuanceRecord.fromJson(sessions.read(CGCOM, journeyOf(holder), Section.ISSUANCE).orElseThrow()).entry("r1").orElseThrow().offerUri())
                .isEmpty();
    }

    @Test
    void confirm_journeyWithdrawnWhileTheIssuerWasAsked_isJourneyClosed() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        HolderRef holder = entitledJourney();
        issuer = (tenant, key, request) -> {
            Journey current = journeys.findById(tenant, journeyOf(holder)).orElseThrow();
            current.withdraw(WithdrawalReason.EXPIRED, clock.instant());
            journeys.update(tenant, current, JourneyState.IN_PROGRESS);
            return api.issue(tenant, key, request);
        };

        assertThatThrownBy(() -> service.confirm(CGCOM, holder, "r1")).satisfies(e -> assertProblem(e,
                ProblemCode.JOURNEY_CLOSED));

        assertThat(processOf(journeyOf(holder))).isEmpty();
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
        HolderRef holder = entitledJourney();

        IssuanceService.Offer offer = service.confirm(CGCOM, holder, "r1");

        assertThat(tickets.calls()).singleElement().satisfies(call -> {
            assertThat(call.tenant()).isEqualTo(CGCOM);
            assertThat(call.walletUnitServiceUrl()).isEqualTo(WALLET_UNIT_SERVICE);
            assertThat(call.email()).isEqualTo(EMAIL);
            assertThat(call.credentialOffer()).isEqualTo(offer.credentialOfferUri());
        });
        assertThat(offer.onboardingUrl()).isEqualTo("https://cgcom.wallet.test/t/ticket-1");
        Journey stored = journeys.findById(CGCOM, journeyOf(holder)).orElseThrow();
        assertThat(stored.state()).isEqualTo(JourneyState.COMPLETED);
        assertThat(stored.step()).isEqualTo(JourneyStep.OFFER_OBTAINED);
        IssuanceRecord.Entry issuance = IssuanceRecord.fromJson(sessions.read(CGCOM, journeyOf(holder), Section.ISSUANCE).orElseThrow()).entry("r1").orElseThrow();
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
        HolderRef holder = entitledJourney();

        IssuanceService.Offer offer = service.confirm(CGCOM, holder, "r1");

        assertThat(trail.ticketRequests()).singleElement().satisfies(event -> {
            assertThat(event.result()).isEqualTo(JourneyAuditTrail.TicketOutcome.CREATED);
            assertThat(event.journeyId()).isEqualTo(journeyOf(holder));
            assertThat(event.toString()).doesNotContain(EMAIL, "openid-credential-offer", offer.onboardingUrl());
        });
        assertThat(trail.issuanceRequests()).singleElement()
                .satisfies(event -> assertThat(event.result()).isEqualTo(JourneyAuditTrail.IssuanceOutcome.ISSUED));
    }

    @Test
    @DisplayName("AC-11 — confirm_tenantWithOnboarding_asksATicketThatExpiresSixtySecondsBeforeTheOffer")
    void confirm_tenantWithOnboarding_asksATicketThatExpiresSixtySecondsBeforeTheOffer() {
        withOnboarding();
        HolderRef holder = entitledJourney();

        IssuanceService.Offer offer = service.confirm(CGCOM, holder, "r1");

        assertThat(tickets.calls().getFirst().expiresIn())
                .isEqualTo(Duration.between(clock.instant(), offer.expiresAt()).minusSeconds(60))
                .isEqualTo(Duration.ofSeconds(540));
        assertThat(offer.onboardingExpiresAt()).isBefore(offer.expiresAt());
    }

    @Test
    @DisplayName("AC-10 — confirm_tenantWithoutOnboarding_doesNotCallTheWalletUnitServiceAndAnswersOnlyTheOffer")
    void confirm_tenantWithoutOnboarding_doesNotCallTheWalletUnitServiceAndAnswersOnlyTheOffer() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        HolderRef holder = entitledJourney();

        IssuanceService.Offer offer = service.confirm(CGCOM, holder, "r1");

        assertThat(tickets.calls()).isEmpty();
        assertThat(trail.ticketRequests()).isEmpty();
        assertThat(offer.onboardingUrl()).isNull();
        assertThat(offer.onboardingExpiresAt()).isNull();
        assertThat(journeys.findById(CGCOM, journeyOf(holder)).orElseThrow().state()).isEqualTo(JourneyState.COMPLETED);
    }

    @ParameterizedTest
    @EnumSource(OnboardingTicketException.Reason.class)
    @DisplayName("EUD-277 AC-12 — confirm_ticketNotCreated_isOnboardingUnavailableWithoutOfferAndKeepsOnlyTheProcess")
    void confirm_ticketNotCreated_isOnboardingUnavailableWithoutOfferAndKeepsOnlyTheProcess(
            OnboardingTicketException.Reason reason) {
        withOnboarding();
        HolderRef holder = entitledJourney();
        tickets.failWith(reason);

        assertThatThrownBy(() -> service.confirm(CGCOM, holder, "r1")).satisfies(e -> assertProblem(e,
                ProblemCode.ONBOARDING_UNAVAILABLE));

        Journey stored = journeys.findById(CGCOM, journeyOf(holder)).orElseThrow();
        assertThat(stored.state()).isEqualTo(JourneyState.IN_PROGRESS);
        assertThat(stored.step()).isEqualTo(JourneyStep.EMAIL_VERIFIED);
        assertThat(processOf(stored.id())).isPresent();
        IssuanceRecord.Entry issuance = IssuanceRecord.fromJson(sessions.read(CGCOM, journeyOf(holder), Section.ISSUANCE).orElseThrow()).entry("r1").orElseThrow();
        assertThat(issuance.offerUri()).isEmpty();
        assertThat(issuance.offerExpiresAt()).isNull();
        assertThat(issuance.onboarding()).isEmpty();
        assertThat(issuance.onboardingExpiresAt()).isNull();
        assertThat(trail.issuanceRequests()).singleElement().satisfies(event -> {
            assertThat(event.result()).isEqualTo(JourneyAuditTrail.IssuanceOutcome.ONBOARDING_FAILED);
            assertThat(event.issuanceProcessId()).contains(processOf(stored.id()).orElseThrow());
        });
        assertThat(trail.ticketRequests()).singleElement()
                .satisfies(event -> assertThat(event.result()).isEqualTo(JourneyAuditTrail.TicketOutcome.FAILED));
        assertThat(trail.events().stream().filter(e -> e.kind() == JourneyAuditTrail.Kind.COMPLETED)).isEmpty();
    }

    @Test
    @DisplayName("ES-07 — confirm_afterTheTicketFailed_renewsTheOfferOfTheSameProcessAndAsksForAnotherTicket")
    void confirm_afterTheTicketFailed_renewsTheOfferOfTheSameProcessAndAsksForAnotherTicket() {
        withOnboarding();
        HolderRef holder = entitledJourney();
        tickets.failWith(OnboardingTicketException.Reason.UNAVAILABLE);
        assertThatThrownBy(() -> service.confirm(CGCOM, holder, "r1")).satisfies(e -> assertProblem(e,
                ProblemCode.ONBOARDING_UNAVAILABLE));
        tickets.recover();

        IssuanceService.Offer offer = service.confirm(CGCOM, holder, "r1");

        // No second issuance: the same process gets a new offer, and a new ticket is asked for it.
        assertThat(api.calls()).hasSize(1);
        assertThat(api.renewals()).containsExactly(processOf(journeyOf(holder)).orElseThrow());
        assertThat(tickets.calls()).hasSize(2);
        assertThat(offer.onboardingUrl()).isNotNull();
        assertThat(stored(holder).state()).isEqualTo(JourneyState.COMPLETED);
        assertThat(trail.offerRenewals()).extracting(JourneyAuditTrail.OfferRenewalRequested::result)
                .containsExactly(JourneyAuditTrail.RenewalOutcome.RENEWED);
    }

    @Test
    @DisplayName("AC-11 — confirm_offerWithTooLittleLife_requestsNoTicketAndIsOnboardingUnavailable")
    void confirm_offerWithTooLittleLife_requestsNoTicketAndIsOnboardingUnavailable() {
        withOnboarding();
        HolderRef holder = entitledJourney();
        issuer = (tenant, key, request) -> new IssuanceResult(UUID.randomUUID(), "openid-credential-offer://?x=1",
                clock.instant().plusSeconds(60));

        assertThatThrownBy(() -> service.confirm(CGCOM, holder, "r1")).satisfies(e -> assertProblem(e,
                ProblemCode.ONBOARDING_UNAVAILABLE));

        assertThat(tickets.calls()).isEmpty();
        assertThat(trail.ticketRequests()).singleElement()
                .satisfies(event -> assertThat(event.result()).isEqualTo(JourneyAuditTrail.TicketOutcome.FAILED));
        assertThat(trail.issuanceRequests()).singleElement().satisfies(event ->
                assertThat(event.result()).isEqualTo(JourneyAuditTrail.IssuanceOutcome.ONBOARDING_FAILED));
        assertThat(journeys.findById(CGCOM, journeyOf(holder)).orElseThrow().state())
                .isEqualTo(JourneyState.IN_PROGRESS);
    }

    @Test
    void confirm_ticketFailsAndAuditTrailFails_stillAnswersOnboardingUnavailable() {
        withOnboarding();
        HolderRef holder = entitledJourney();
        tickets.failWith(OnboardingTicketException.Reason.REJECTED);
        trail.failFromNowOn();

        assertThatThrownBy(() -> service.confirm(CGCOM, holder, "r1")).satisfies(e -> assertProblem(e,
                ProblemCode.ONBOARDING_UNAVAILABLE));
    }

    // --- helpers ---

    private void assertProblem(Throwable thrown, ProblemCode expected) {
        assertThat(thrown).isInstanceOfSatisfying(ProblemException.class,
                e -> assertThat(e.problem()).isEqualTo(expected));
    }

    private void assertNothingAsked(HolderRef holder) {
        assertThat(api.calls()).isEmpty();
        assertThat(sessions.read(CGCOM, journeyOf(holder), Section.ISSUANCE)).isEmpty();
        assertThat(EmailRecord.fromJson(sessions.read(CGCOM, journeyOf(holder), Section.EMAIL).orElseThrow()).address())
                .isEqualTo(EMAIL);
        Journey stored = journeys.findById(CGCOM, journeyOf(holder)).orElseThrow();
        assertThat(stored.state()).isEqualTo(JourneyState.IN_PROGRESS);
        assertThat(processOf(stored.id())).isEmpty();
    }

    /** ES-01, ES-02, AC-05: the journey is where the entitlement left it, without a process or an offer. */
    private void assertStillEntitled(HolderRef holder) {
        Journey stored = journeys.findById(CGCOM, journeyOf(holder)).orElseThrow();
        assertThat(stored.state()).isEqualTo(JourneyState.IN_PROGRESS);
        assertThat(stored.step()).isEqualTo(JourneyStep.EMAIL_VERIFIED);
        assertThat(processOf(stored.id())).isEmpty();
        assertThat(sessions.read(CGCOM, journeyOf(holder), Section.ENTITLEMENT)).isPresent();
    }

    // --- EUD-359 task 3: several requests in one journey ---

    private static EntitlementRecord.Request request(String id, EntitlementRecord.Status status, String number) {
        return new EntitlementRecord.Request(id, "SIM-" + id, status, List.of(), false, "Hospital", null, null, null,
                status.carriesData() ? Map.of("personal_administrative_number", number,
                        "medical_board", List.of("Colegio de Medicos de Prueba"),
                        "medical_speciality", List.of("Cardiology"), "legally_entitled", true) : Map.of(),
                status.carriesData() ? new EntitlementRecord.Preview("DoctorID", List.of()) : null);
    }

    /** The entitled journey with these requests instead of the single available one. */
    private HolderRef journeyWithRequests(EntitlementRecord.Request... requests) {
        HolderRef holder = entitledJourney();
        sessions.write(CGCOM, journeyOf(holder), Section.ENTITLEMENT, new EntitlementRecord(
                IdentifierType.PERSONAL_ID, null, List.of(requests), clock.instant()).toJson());
        return holder;
    }

    @Test
    @DisplayName("AC-05 — confirm_twoRequests_twoProcessesWithDifferentKeysAndTheEmailVerifiedOnce")
    void confirm_twoRequests_twoProcessesWithDifferentKeysAndTheEmailVerifiedOnce() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        HolderRef holder = journeyWithRequests(request("r1", EntitlementRecord.Status.AVAILABLE, "280000001"),
                request("r2", EntitlementRecord.Status.AVAILABLE, "280000002"));

        IssuanceService.Offer first = service.confirm(CGCOM, holder, "r1");
        assertThat(stored(holder).state()).isEqualTo(JourneyState.COMPLETED);
        IssuanceService.Offer second = service.confirm(CGCOM, holder, "r2");

        assertThat(second.credentialOfferUri()).isNotEqualTo(first.credentialOfferUri());
        assertThat(api.calls()).hasSize(2);
        assertThat(api.calls().get(0).idempotencyKey()).isNotEqualTo(api.calls().get(1).idempotencyKey());
        assertThat(api.calls().get(0).request().payload().toString()).contains("280000001").doesNotContain("280000002");
        assertThat(api.calls().get(1).request().payload().toString()).contains("280000002").doesNotContain("280000001");
        // The email of the journey was verified once and serves both: the second needed no new code.
        assertThat(api.calls()).extracting(call -> call.request().email()).containsOnly(EMAIL);
        assertThat(issuances.findByJourney(CGCOM, stored(holder).id())).extracting(JourneyIssuance::requestId)
                .containsExactly("r1", "r2");
        IssuanceRecord section = IssuanceRecord.fromJson(
                sessions.read(CGCOM, journeyOf(holder), Section.ISSUANCE).orElseThrow());
        assertThat(section.requests().keySet()).containsExactly("r1", "r2");
        assertThat(section.entry("r1").orElseThrow().idempotencyKey()).isEqualTo(api.calls().get(0).idempotencyKey());
        assertThat(section.entry("r2").orElseThrow().idempotencyKey()).isEqualTo(api.calls().get(1).idempotencyKey());
        assertThat(section.entry("r2").orElseThrow().offerUri()).contains(second.credentialOfferUri());
        // Completed with the first offer, and only once.
        assertThat(trail.events().stream().filter(e -> e.kind() == JourneyAuditTrail.Kind.COMPLETED)).hasSize(1);
        assertThat(stored(holder).completedAt()).isPresent();
        assertThat(stored(holder).endedAt()).isEmpty();
    }

    @Test
    @DisplayName("AC-05 — confirm_secondRequestOfACompletedJourney_isIssuedAndTheJourneyStaysCompleted")
    void confirm_secondRequestOfACompletedJourney_isIssuedAndTheJourneyStaysCompleted() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        HolderRef holder = journeyWithRequests(request("r1", EntitlementRecord.Status.AVAILABLE, "280000001"),
                request("r2", EntitlementRecord.Status.AVAILABLE, "280000002"));
        service.confirm(CGCOM, holder, "r1");
        Instant completedAt = stored(holder).completedAt().orElseThrow();
        clock.advance(Duration.ofMinutes(5));

        service.confirm(CGCOM, holder, "r2");

        assertThat(stored(holder).state()).isEqualTo(JourneyState.COMPLETED);
        assertThat(stored(holder).step()).isEqualTo(JourneyStep.OFFER_OBTAINED);
        assertThat(stored(holder).completedAt()).contains(completedAt);
    }

    @Test
    @DisplayName("ES-07 — confirm_ticketFailsForASecondRequest_keepsItsProcessWithoutOfferAndTheJourneyStaysCompleted")
    void confirm_ticketFailsForASecondRequest_keepsItsProcessWithoutOfferAndTheJourneyStaysCompleted() {
        withOnboarding();
        HolderRef holder = journeyWithRequests(request("r1", EntitlementRecord.Status.AVAILABLE, "280000001"),
                request("r2", EntitlementRecord.Status.AVAILABLE, "280000002"));
        service.confirm(CGCOM, holder, "r1");
        tickets.failWith(OnboardingTicketException.Reason.UNAVAILABLE);

        assertThatThrownBy(() -> service.confirm(CGCOM, holder, "r2")).satisfies(e -> assertProblem(e,
                ProblemCode.ONBOARDING_UNAVAILABLE));

        assertThat(stored(holder).state()).isEqualTo(JourneyState.COMPLETED);
        assertThat(issuances.findByJourney(CGCOM, stored(holder).id())).extracting(JourneyIssuance::requestId)
                .containsExactly("r1", "r2");
        IssuanceRecord section = IssuanceRecord.fromJson(
                sessions.read(CGCOM, journeyOf(holder), Section.ISSUANCE).orElseThrow());
        assertThat(section.entry("r1").orElseThrow().offerUri()).isPresent();
        assertThat(section.entry("r2").orElseThrow().offerUri()).isEmpty();
        assertThat(trail.issuanceRequests()).extracting(JourneyAuditTrail.IssuanceRequested::requestId)
                .containsExactly("r1", "r2");
    }

    @Test
    @DisplayName("AC-10 — confirm_requestThatIsNotAvailable_isStepNotAllowedAndNothingIsAsked")
    void confirm_requestThatIsNotAvailable_isStepNotAllowedAndNothingIsAsked() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        HolderRef holder = journeyWithRequests(request("r1", EntitlementRecord.Status.IDENTITY_MISMATCH, "1"),
                request("r2", EntitlementRecord.Status.IDENTIFIER_MISMATCH, "2"),
                request("r3", EntitlementRecord.Status.UNDER_REVIEW, "3"),
                request("r4", EntitlementRecord.Status.AVAILABLE, "4"));

        for (String id : List.of("r1", "r2", "r3", "r9")) {
            assertThatThrownBy(() -> service.confirm(CGCOM, holder, id)).as(id).satisfies(e -> assertProblem(e,
                    ProblemCode.STEP_NOT_ALLOWED));
        }

        assertThat(api.calls()).isEmpty();
        assertThat(sessions.read(CGCOM, journeyOf(holder), Section.ISSUANCE)).isEmpty();
        assertThat(issuances.findByJourney(CGCOM, stored(holder).id())).isEmpty();
    }

    @Test
    @DisplayName("AC-10 — confirm_savedRequest_isStepNotAllowedAndNothingIsAsked")
    void confirm_savedRequest_isStepNotAllowedAndNothingIsAsked() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        HolderRef holder = journeyWithRequests(request("r1", EntitlementRecord.Status.AVAILABLE, "280000001"));
        issuances.insert(CGCOM, new JourneyIssuance(stored(holder).id(), "r1", UUID.randomUUID(), clock.instant(),
                clock.instant()));

        assertThatThrownBy(() -> service.confirm(CGCOM, holder, "r1")).satisfies(e -> assertProblem(e,
                ProblemCode.STEP_NOT_ALLOWED));

        assertThat(api.calls()).isEmpty();
    }

    @Test
    @DisplayName("ES-01 — confirm_blankOrTooLongRequestId_isBadRequestAndNothingIsAsked")
    void confirm_blankOrTooLongRequestId_isBadRequestAndNothingIsAsked() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        HolderRef holder = entitledJourney();

        for (String id : new String[] {null, "", "  ", "r".repeat(65)}) {
            assertThatThrownBy(() -> service.confirm(CGCOM, holder, id)).satisfies(e -> assertProblem(e,
                    ProblemCode.BAD_REQUEST));
        }

        assertThat(api.calls()).isEmpty();
        // 64 characters is within the contract: it is only not a request of the journey.
        assertThatThrownBy(() -> service.confirm(CGCOM, holder, "r".repeat(64))).satisfies(e -> assertProblem(e,
                ProblemCode.STEP_NOT_ALLOWED));
    }

    @Test
    @DisplayName("AC-11 — confirm_issuanceRequest_isTracedWithTheOpaqueIdOfTheRequestOnly")
    void confirm_issuanceRequest_isTracedWithTheOpaqueIdOfTheRequestOnly() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        HolderRef holder = journeyWithRequests(request("r1", EntitlementRecord.Status.AVAILABLE, "280000001"));

        service.confirm(CGCOM, holder, "r1");

        assertThat(trail.issuanceRequests()).singleElement().satisfies(event -> {
            assertThat(event.requestId()).isEqualTo("r1");
            assertThat(event.toString()).doesNotContain("SIM-", "280000001");
        });
    }

    // --- EUD-359 task 4: a new offer of a request that has its process ---

    private HolderRef confirmedOnce() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        HolderRef holder = entitledJourney();
        service.confirm(CGCOM, holder, "r1");
        return holder;
    }

    @Test
    @DisplayName("AC-07 — newOffer_addressExpired_renewsTheOfferOfTheSameProcessAndKeepsTheKey")
    void newOffer_addressExpired_renewsTheOfferOfTheSameProcessAndKeepsTheKey() {
        HolderRef holder = confirmedOnce();
        UUID process = processOf(stored(holder).id()).orElseThrow();
        UUID key = IssuanceRecord.fromJson(sessions.read(CGCOM, journeyOf(holder), Section.ISSUANCE).orElseThrow())
                .entry("r1").orElseThrow().idempotencyKey();
        clock.advance(Duration.ofMinutes(10));

        IssuanceService.Offer offer = service.newOffer(CGCOM, holder, "r1");

        assertThat(offer.credentialOfferUri()).contains("renewed");
        assertThat(offer.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(10)));
        assertThat(api.calls()).hasSize(1);
        assertThat(api.renewals()).containsExactly(process);
        IssuanceRecord.Entry entry = IssuanceRecord.fromJson(
                sessions.read(CGCOM, journeyOf(holder), Section.ISSUANCE).orElseThrow()).entry("r1").orElseThrow();
        assertThat(entry.idempotencyKey()).isEqualTo(key);
        assertThat(entry.offerUri()).contains(offer.credentialOfferUri());
        assertThat(issuances.findByJourney(CGCOM, stored(holder).id())).hasSize(1);
        assertThat(trail.offerRenewals()).singleElement().satisfies(event -> {
            assertThat(event.requestId()).isEqualTo("r1");
            assertThat(event.result()).isEqualTo(JourneyAuditTrail.RenewalOutcome.RENEWED);
            assertThat(event.toString()).doesNotContain("openid-credential-offer");
        });
    }

    @Test
    @DisplayName("ES-06 — newOffer_addressStillWorking_isStepNotAllowedAndNothingIsAsked")
    void newOffer_addressStillWorking_isStepNotAllowedAndNothingIsAsked() {
        HolderRef holder = confirmedOnce();
        clock.advance(Duration.ofMinutes(9));

        assertThatThrownBy(() -> service.newOffer(CGCOM, holder, "r1")).satisfies(e -> assertProblem(e,
                ProblemCode.STEP_NOT_ALLOWED));

        assertThat(api.renewals()).isEmpty();
    }

    @Test
    @DisplayName("ES-06 — newOffer_requestWithoutProcessOrUnknown_isStepNotAllowed")
    void newOffer_requestWithoutProcessOrUnknown_isStepNotAllowed() {
        configs.put(CGCOM, config("doctorid.sd.1", DOCTORID_PLACEMENT, Onboarding.DISABLED));
        HolderRef holder = journeyWithRequests(request("r1", EntitlementRecord.Status.AVAILABLE, "280000001"),
                request("r2", EntitlementRecord.Status.AVAILABLE, "280000002"));
        service.confirm(CGCOM, holder, "r1");
        clock.advance(Duration.ofMinutes(10));

        for (String id : List.of("r2", "r9")) {
            assertThatThrownBy(() -> service.newOffer(CGCOM, holder, id)).as(id).satisfies(e -> assertProblem(e,
                    ProblemCode.STEP_NOT_ALLOWED));
        }

        assertThat(api.renewals()).isEmpty();
    }

    @Test
    @DisplayName("ES-01 — newOffer_blankOrTooLongRequestId_isBadRequest")
    void newOffer_blankOrTooLongRequestId_isBadRequest() {
        HolderRef holder = confirmedOnce();

        for (String id : new String[] {null, "", " ", "r".repeat(65)}) {
            assertThatThrownBy(() -> service.newOffer(CGCOM, holder, id)).satisfies(e -> assertProblem(e,
                    ProblemCode.BAD_REQUEST));
        }
    }

    @Test
    @DisplayName("ES-07 — newOffer_ticketFails_isOnboardingUnavailableWithoutOfferAndTheJourneyStaysAsItWas")
    void newOffer_ticketFails_isOnboardingUnavailableWithoutOfferAndTheJourneyStaysAsItWas() {
        withOnboarding();
        HolderRef holder = entitledJourney();
        service.confirm(CGCOM, holder, "r1");
        clock.advance(Duration.ofMinutes(9));
        tickets.failWith(OnboardingTicketException.Reason.UNAVAILABLE);

        assertThatThrownBy(() -> service.newOffer(CGCOM, holder, "r1")).satisfies(e -> assertProblem(e,
                ProblemCode.ONBOARDING_UNAVAILABLE));

        assertThat(stored(holder).state()).isEqualTo(JourneyState.COMPLETED);
        assertThat(issuances.findByJourney(CGCOM, stored(holder).id())).hasSize(1);
        // The Issuer voided the old offer: it is dropped, so the next call can renew without waiting.
        IssuanceRecord.Entry entry = IssuanceRecord.fromJson(
                sessions.read(CGCOM, journeyOf(holder), Section.ISSUANCE).orElseThrow()).entry("r1").orElseThrow();
        assertThat(entry.offerUri()).isEmpty();
        tickets.recover();

        IssuanceService.Offer offer = service.newOffer(CGCOM, holder, "r1");

        assertThat(offer.onboardingUrl()).isNotNull();
        assertThat(api.renewals()).hasSize(2);
        assertThat(api.calls()).hasSize(1);
    }

    @Test
    @DisplayName("AC-07 — newOffer_onboardingAddressExpiredBeforeTheOffer_renews")
    void newOffer_onboardingAddressExpiredBeforeTheOffer_renews() {
        withOnboarding();
        HolderRef holder = entitledJourney();
        service.confirm(CGCOM, holder, "r1");
        // The ticket lasts 60 s less than the offer: the address the holder has expired, the offer did not yet.
        clock.advance(Duration.ofSeconds(9 * 60 + 1));

        IssuanceService.Offer offer = service.newOffer(CGCOM, holder, "r1");

        assertThat(offer.onboardingUrl()).isNotNull();
        assertThat(api.renewals()).hasSize(1);
    }

    @Test
    @DisplayName("ES-07 — newOffer_journeyStillInProgressBecauseTheFirstTicketFailed_completesItWithTheNewOffer")
    void newOffer_journeyStillInProgressBecauseTheFirstTicketFailed_completesItWithTheNewOffer() {
        withOnboarding();
        HolderRef holder = entitledJourney();
        tickets.failWith(OnboardingTicketException.Reason.UNAVAILABLE);
        assertThatThrownBy(() -> service.confirm(CGCOM, holder, "r1")).satisfies(e -> assertProblem(e,
                ProblemCode.ONBOARDING_UNAVAILABLE));
        tickets.recover();

        service.newOffer(CGCOM, holder, "r1");

        assertThat(stored(holder).state()).isEqualTo(JourneyState.COMPLETED);
        assertThat(stored(holder).step()).isEqualTo(JourneyStep.OFFER_OBTAINED);
        assertThat(api.calls()).hasSize(1);
    }

    @Test
    @DisplayName("ES-07 — newOffer_issuerFails_isRejectedOrUnavailableAndTraced")
    void newOffer_issuerFails_isRejectedOrUnavailableAndTraced() {
        HolderRef holder = confirmedOnce();
        clock.advance(Duration.ofMinutes(10));
        api.failRenewalWith(new IssuanceRejectedException(IssuanceRejectedException.Reason.REQUEST_REJECTED));
        assertThatThrownBy(() -> service.newOffer(CGCOM, holder, "r1")).satisfies(e -> assertProblem(e,
                ProblemCode.ISSUANCE_REJECTED));
        api.failRenewalWith(new IssuanceUnavailableException(IssuanceUnavailableException.Reason.NETWORK));
        assertThatThrownBy(() -> service.newOffer(CGCOM, holder, "r1")).satisfies(e -> assertProblem(e,
                ProblemCode.ISSUANCE_UNAVAILABLE));
        api.failRenewalWith(new SystemTokenUnavailableException(
                SystemTokenUnavailableException.Reason.NO_MACHINE_CREDENTIAL));
        assertThatThrownBy(() -> service.newOffer(CGCOM, holder, "r1")).satisfies(e -> assertProblem(e,
                ProblemCode.ISSUANCE_UNAVAILABLE));

        assertThat(trail.offerRenewals()).extracting(JourneyAuditTrail.OfferRenewalRequested::result)
                .containsExactly(JourneyAuditTrail.RenewalOutcome.REJECTED,
                        JourneyAuditTrail.RenewalOutcome.UNAVAILABLE, JourneyAuditTrail.RenewalOutcome.UNAVAILABLE);
        assertThat(stored(holder).state()).isEqualTo(JourneyState.COMPLETED);
    }

    @Test
    @DisplayName("EC-12 — newOffer_usesTheVerifiedEmailOfTheJourneyForTheTicket")
    void newOffer_usesTheVerifiedEmailOfTheJourneyForTheTicket() {
        withOnboarding();
        HolderRef holder = entitledJourney();
        service.confirm(CGCOM, holder, "r1");
        clock.advance(Duration.ofMinutes(10));

        service.newOffer(CGCOM, holder, "r1");

        assertThat(tickets.calls()).extracting(InMemoryOnboardingTickets.Call::email).containsOnly(EMAIL);
    }

    // --- EUD-359 task 4b: a request that is saved ---

    @Test
    @DisplayName("AC-08 — newOffer_offerNotRenewable_savesTheRequestDeletesItsOfferAndAnswersRequestSaved")
    void newOffer_offerNotRenewable_savesTheRequestDeletesItsOfferAndAnswersRequestSaved() {
        HolderRef holder = confirmedOnce();
        clock.advance(Duration.ofMinutes(10));
        api.notRenewable();

        assertThatThrownBy(() -> service.newOffer(CGCOM, holder, "r1")).satisfies(e -> assertProblem(e,
                ProblemCode.REQUEST_SAVED));

        assertSavedWithoutOffer(holder, JourneyAuditTrail.SavedOrigin.OFFER_NOT_RENEWABLE);
        assertThat(trail.offerRenewals()).extracting(JourneyAuditTrail.OfferRenewalRequested::result)
                .containsExactly(JourneyAuditTrail.RenewalOutcome.REQUEST_SAVED);
        assertThat(api.calls()).hasSize(1);
        assertThat(stored(holder).state()).isEqualTo(JourneyState.COMPLETED);
        assertThat(stored(holder).endedAt()).isEmpty();
    }

    @Test
    @DisplayName("AC-08 — confirm_requestWhoseOfferIsNotRenewable_isSavedAndNoOtherIssuanceIsAsked")
    void confirm_requestWhoseOfferIsNotRenewable_isSavedAndNoOtherIssuanceIsAsked() {
        HolderRef holder = confirmedOnce();
        clock.advance(Duration.ofMinutes(10));
        api.notRenewable();

        assertThatThrownBy(() -> service.confirm(CGCOM, holder, "r1")).satisfies(e -> assertProblem(e,
                ProblemCode.REQUEST_SAVED));

        assertSavedWithoutOffer(holder, JourneyAuditTrail.SavedOrigin.OFFER_NOT_RENEWABLE);
        assertThat(api.calls()).hasSize(1);
        // Saved: no confirmation and no new offer, ever.
        assertThatThrownBy(() -> service.confirm(CGCOM, holder, "r1")).satisfies(e -> assertProblem(e,
                ProblemCode.STEP_NOT_ALLOWED));
        assertThatThrownBy(() -> service.newOffer(CGCOM, holder, "r1")).satisfies(e -> assertProblem(e,
                ProblemCode.STEP_NOT_ALLOWED));
        assertThat(api.calls()).hasSize(1);
        assertThat(api.renewals()).hasSize(1);
    }

    @Test
    @DisplayName("AC-08 — newOffer_offerNotRenewableInAJourneyStillInProgress_completesIt")
    void newOffer_offerNotRenewableInAJourneyStillInProgress_completesIt() {
        withOnboarding();
        HolderRef holder = entitledJourney();
        tickets.failWith(OnboardingTicketException.Reason.UNAVAILABLE);
        assertThatThrownBy(() -> service.confirm(CGCOM, holder, "r1")).satisfies(e -> assertProblem(e,
                ProblemCode.ONBOARDING_UNAVAILABLE));
        tickets.recover();
        api.notRenewable();

        assertThatThrownBy(() -> service.newOffer(CGCOM, holder, "r1")).satisfies(e -> assertProblem(e,
                ProblemCode.REQUEST_SAVED));

        assertThat(stored(holder).state()).isEqualTo(JourneyState.COMPLETED);
        assertThat(stored(holder).endedAt()).isEmpty();
        assertThat(trail.events().stream().filter(e -> e.kind() == JourneyAuditTrail.Kind.COMPLETED)).hasSize(1);
    }

    private void assertSavedWithoutOffer(HolderRef holder, JourneyAuditTrail.SavedOrigin origin) {
        JourneyIssuance process = issuances.findByJourney(CGCOM, stored(holder).id()).getFirst();
        assertThat(process.isSaved()).isTrue();
        IssuanceRecord.Entry entry = IssuanceRecord.fromJson(
                sessions.read(CGCOM, journeyOf(holder), Section.ISSUANCE).orElseThrow()).entry("r1").orElseThrow();
        assertThat(entry.offerUri()).isEmpty();
        assertThat(entry.onboarding()).isEmpty();
        assertThat(trail.savedRequests()).singleElement().satisfies(event -> {
            assertThat(event.requestId()).isEqualTo("r1");
            assertThat(event.origin()).isEqualTo(origin);
            assertThat(event.toString()).doesNotContain("openid-credential-offer", EMAIL);
        });
    }

    @Test
    @DisplayName("AC-05 — declareSaved_requestInOfferWithItsOffer_savesItAndNeverAsksTheIssuer")
    void declareSaved_requestInOfferWithItsOffer_savesItAndNeverAsksTheIssuer() {
        HolderRef holder = confirmedOnce();
        int callsBefore = api.calls().size();

        service.declareSaved(CGCOM, holder, "r1");

        assertSavedWithoutOffer(holder, JourneyAuditTrail.SavedOrigin.HOLDER_DECLARED);
        assertThat(api.calls()).hasSize(callsBefore);
        assertThat(api.renewals()).isEmpty();
        assertThat(stored(holder).state()).isEqualTo(JourneyState.COMPLETED);
    }

    @Test
    @DisplayName("AC-14 — declareSaved_thenConfirmOrNewOffer_isStepNotAllowed")
    void declareSaved_thenConfirmOrNewOffer_isStepNotAllowed() {
        HolderRef holder = confirmedOnce();
        service.declareSaved(CGCOM, holder, "r1");
        clock.advance(Duration.ofMinutes(10));

        assertThatThrownBy(() -> service.confirm(CGCOM, holder, "r1")).satisfies(e -> assertProblem(e,
                ProblemCode.STEP_NOT_ALLOWED));
        assertThatThrownBy(() -> service.newOffer(CGCOM, holder, "r1")).satisfies(e -> assertProblem(e,
                ProblemCode.STEP_NOT_ALLOWED));
        assertThatThrownBy(() -> service.declareSaved(CGCOM, holder, "r1")).satisfies(e -> assertProblem(e,
                ProblemCode.STEP_NOT_ALLOWED));

        assertThat(api.renewals()).isEmpty();
    }

    @Test
    @DisplayName("AC-14 — declareSaved_requestWithoutOfferDeliveredOrNotInOfferOrUnknown_isStepNotAllowed")
    void declareSaved_requestWithoutOfferDeliveredOrNotInOfferOrUnknown_isStepNotAllowed() {
        withOnboarding();
        HolderRef holder = journeyWithRequests(request("r1", EntitlementRecord.Status.AVAILABLE, "280000001"),
                request("r2", EntitlementRecord.Status.AVAILABLE, "280000002"));
        tickets.failWith(OnboardingTicketException.Reason.UNAVAILABLE);
        assertThatThrownBy(() -> service.confirm(CGCOM, holder, "r1")).satisfies(e -> assertProblem(e,
                ProblemCode.ONBOARDING_UNAVAILABLE));

        // r1 has its process but no offer was delivered; r2 has no process; r9 is not a request of the journey.
        for (String id : List.of("r1", "r2", "r9")) {
            assertThatThrownBy(() -> service.declareSaved(CGCOM, holder, id)).as(id).satisfies(e -> assertProblem(e,
                    ProblemCode.STEP_NOT_ALLOWED));
        }

        assertThat(trail.savedRequests()).isEmpty();
        assertThat(issuances.findByJourney(CGCOM, stored(holder).id())).allMatch(process -> !process.isSaved());
    }

    @Test
    @DisplayName("ES-01 — declareSaved_blankOrTooLongRequestId_isBadRequest")
    void declareSaved_blankOrTooLongRequestId_isBadRequest() {
        HolderRef holder = confirmedOnce();

        for (String id : new String[] {null, "", " ", "r".repeat(65)}) {
            assertThatThrownBy(() -> service.declareSaved(CGCOM, holder, id)).satisfies(e -> assertProblem(e,
                    ProblemCode.BAD_REQUEST));
        }
    }

    @Test
    @DisplayName("AC-25 — declareSaved_countsAsActivity")
    void declareSaved_countsAsActivity() {
        HolderRef holder = confirmedOnce();
        clock.advance(Duration.ofMinutes(14));

        service.declareSaved(CGCOM, holder, "r1");

        assertThat(stored(holder).lastActivity()).isEqualTo(clock.instant());
    }

    @Test
    @DisplayName("ES-08 — declareSaved_endedJourney_isJourneyClosed")
    void declareSaved_endedJourney_isJourneyClosed() {
        HolderRef holder = confirmedOnce();
        clock.advance(Duration.ofMinutes(15));

        assertThatThrownBy(() -> service.declareSaved(CGCOM, holder, "r1")).isInstanceOf(JourneyClosedException.class);
    }

    private Journey stored(HolderRef holder) {
        return journeys.findById(CGCOM, journeyOf(holder)).orElseThrow();
    }

    private UUID journeyOf(HolderRef holder) {
        return sessions.journeyOf(CGCOM, holder).orElseThrow();
    }

    private HolderRef identifiedJourney() {
        HolderRef holder = startJourney(CGCOM);
        sessions.write(CGCOM, journeyOf(holder), Section.IDENTITY, new HolderIdentity("Elena", "Ruiz Prieto",
                new PersonalIdentifier(IdentifierType.PERSONAL_ID, "00000001R"), "elena.token@example.test").toJson());
        journey.reach(CGCOM, holder, JourneyProgress.Step.IDENTIFIED);
        return holder;
    }

    /** What the holder has before confirming: the entitlement and the email verified with a code. */
    private HolderRef entitledJourney() {
        HolderRef holder = entitledJourneyWithoutEmail();
        sessions.write(CGCOM, journeyOf(holder), Section.EMAIL, new EmailRecord(EMAIL).toJson());
        sessions.write(CGCOM, journeyOf(holder), Section.EMAIL_VERIFICATION, new EmailVerificationRecord(EMAIL, null, null, 0, 1,
                clock.instant()).toJson());
        Journey stored = journeys.findById(CGCOM, journeyOf(holder)).orElseThrow();
        stored.reach(JourneyStep.EMAIL_VERIFIED, clock.instant());
        journeys.update(CGCOM, stored, JourneyState.IN_PROGRESS);
        return holder;
    }

    private HolderRef entitledJourneyWithoutEmail() {
        HolderRef holder = identifiedJourney();
        sessions.write(CGCOM, journeyOf(holder), Section.ENTITLEMENT, entitlement().toJson());
        journey.reach(CGCOM, holder, JourneyProgress.Step.ENTITLEMENT_CONFIRMED);
        return holder;
    }

    private EntitlementRecord entitlement() {
        return EntitlementRecords.available(IdentifierType.PERSONAL_ID, CLAIMS,
                new EntitlementRecord.Preview("DoctorID", List.of()), null, clock.instant());
    }

    private static PortalConfig config(String attestationType, Map<String, String> placement, Onboarding onboarding) {
        return new PortalConfig(Map.of(), new LegalLinks(null, null, null, null),
                new Support(null, null, null, null), "https://cgcom.test/wallet",
                new ServiceSettings(attestationType, placement, onboarding, null, null));
    }

    /** A journey opened for a new holder, as {@code POST /journey} opens it, with a default identity. */
    private HolderRef startJourney(TenantId tenant) {
        HolderRef holder = Holders.next();
        journey.open(tenant, holder, new HolderIdentity("Lucia", "Prueba Sintetica",
                new PersonalIdentifier(IdentifierType.PERSONAL_ID, "00000001R"), null), null);
        return holder;
    }

    private Optional<UUID> processOf(UUID journeyId) {
        return issuances.findByJourney(CGCOM, journeyId).stream().map(JourneyIssuance::issuanceProcessId).findFirst();
    }
}
