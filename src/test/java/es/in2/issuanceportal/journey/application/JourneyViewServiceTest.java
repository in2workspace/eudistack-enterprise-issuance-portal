package es.in2.issuanceportal.journey.application;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import es.in2.issuanceportal.MutableClock;
import es.in2.issuanceportal.journey.InMemoryJourneyIssuanceRepository;
import es.in2.issuanceportal.journey.InMemoryJourneyRepository;
import es.in2.issuanceportal.journey.RecordingJourneyAuditTrail;
import es.in2.issuanceportal.journey.api.JourneyProgress;
import es.in2.issuanceportal.journey.domain.Journey;
import es.in2.issuanceportal.journey.domain.JourneyClosedException;
import es.in2.issuanceportal.journey.domain.JourneyState;
import es.in2.issuanceportal.journey.domain.JourneyIssuance;
import es.in2.issuanceportal.journey.domain.config.LegalLinks;
import es.in2.issuanceportal.journey.domain.config.Onboarding;
import es.in2.issuanceportal.journey.domain.config.PortalConfig;
import es.in2.issuanceportal.journey.domain.config.ServiceSettings;
import es.in2.issuanceportal.journey.domain.config.Support;
import es.in2.issuanceportal.session.Holders;
import es.in2.issuanceportal.session.InMemoryJourneySessions;
import es.in2.issuanceportal.session.EntitlementRecords;
import es.in2.issuanceportal.session.api.EntitlementRecord;
import es.in2.issuanceportal.session.api.HolderIdentity;
import es.in2.issuanceportal.session.api.IdentifierType;
import es.in2.issuanceportal.session.api.IssuanceRecord;
import es.in2.issuanceportal.session.api.PersonalIdentifier;
import es.in2.issuanceportal.session.api.Section;
import es.in2.issuanceportal.session.api.HolderRef;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JourneyViewServiceTest {

    private static final TenantId SANDBOX = new TenantId("sandbox");
    private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
    private static final String OFFER_URI = "openid-credential-offer://?credential_offer_uri=https%3A%2F%2Fissuer%2Foffer%2F1";
    private static final String ONBOARDING_URL = "https://sandbox.127.0.0.1.nip.io:4443/t/Zk3q9Xw2";
    private static final String HOLDER_NAME = "Lucía Prueba Sintética";
    private static final String HOLDER_EMAIL = "lucia.prueba@example.org";

    /** Sessions whose read can be made to fail the way the cipher does with a tampered value. */
    private final AtomicReference<Section> undecryptable = new AtomicReference<>();
    private final InMemoryJourneySessions sessions = new InMemoryJourneySessions() {
        @Override
        public Optional<String> read(TenantId tenant, UUID journeyId, Section section) {
            if (section == undecryptable.get()) {
                throw new IllegalArgumentException("Cannot process the personal data envelope");
            }
            return super.read(tenant, journeyId, section);
        }
    };
    private final InMemoryJourneyRepository journeys = new InMemoryJourneyRepository(sessions);
    private final MutableClock clock = new MutableClock(NOW);
    private final JourneyService journeyService = new JourneyService(journeys, sessions, new RecordingJourneyAuditTrail(),
            TransactionOperations.withoutTransaction(), TransactionOperations.withoutTransaction(), clock);
    private final AtomicReference<Optional<PortalConfig>> config = new AtomicReference<>(Optional.of(config(true)));
    private final InMemoryJourneyIssuanceRepository issuances = new InMemoryJourneyIssuanceRepository();
    private final JourneyViewService service = new JourneyViewService(journeyService, issuances, sessions,
            new PortalConfigService(tenant -> config.get()));

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(JourneyViewService.class);

    @BeforeEach
    void captureLogs() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }


    @Test
    @DisplayName("EC-12 — a journey just started has no requests nor known email")
    void current_started_hasNoRequests() {
        HolderRef holder = start();

        JourneyViewService.View view = service.current(SANDBOX, holder);

        assertThat(view.requests()).isEmpty();
        assertThat(view.knownEmail()).isEmpty();
    }

    @Test
    @DisplayName("AC-01 — with the requests checked, the view carries each with its status, data and the known email")
    void current_requestsChecked_carriesEachWithItsStatusAndTheKnownEmail() {
        HolderRef holder = confirmed();

        JourneyViewService.View view = service.current(SANDBOX, holder);

        assertThat(view.knownEmail()).contains(HOLDER_EMAIL);
        assertThat(view.requests()).singleElement().satisfies(request -> {
            assertThat(request.id()).isEqualTo("r1");
            assertThat(request.status()).isEqualTo(JourneyViewService.RequestStatus.AVAILABLE);
            assertThat(request.notAvailableReason()).isEmpty();
            JourneyViewService.Attestation attestation = request.attestation().orElseThrow();
            assertThat(attestation.typeName()).isEqualTo("DoctorID");
            assertThat(attestation.fields()).extracting(EntitlementRecord.Field::label, EntitlementRecord.Field::value)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("Nombre y apellidos", HOLDER_NAME),
                            org.assertj.core.groups.Tuple.tuple("Número de colegiado", "280000001"));
            assertThat(request.offer()).isEmpty();
        });
    }

    @Test
    @DisplayName("AC-01 — a source without email leaves no known email")
    void current_entitlementWithoutEmail_hasNoKnownEmail() {
        HolderRef holder = confirmed();
        EntitlementRecord known = entitlementOf(holder);
        sessions.write(SANDBOX, journeyOf(holder), Section.ENTITLEMENT, new EntitlementRecord(known.identifierType(),
                null, known.requests(), known.checkedAt()).toJson());

        assertThat(service.current(SANDBOX, holder).knownEmail()).isEmpty();
    }

    @Test
    @DisplayName("AC-01 — the status of each request follows design 3.7: not available says why, and shows no data")
    void current_requestsThatCannotBeIssued_areNotAvailableWithTheReason() {
        HolderRef holder = confirmed();
        EntitlementRecord known = entitlementOf(holder);
        EntitlementRecord.Request available = known.requests().getFirst();
        sessions.write(SANDBOX, journeyOf(holder), Section.ENTITLEMENT, new EntitlementRecord(known.identifierType(),
                known.email(), List.of(
                        new EntitlementRecord.Request("r1", "SIM-1", EntitlementRecord.Status.IDENTITY_MISMATCH,
                                List.of("given_name"), false, "Hospital", NOW, "280000001", null, null, null),
                        new EntitlementRecord.Request("r2", "SIM-2", EntitlementRecord.Status.IDENTIFIER_MISMATCH,
                                List.of(), false, null, null, null, null, null, null),
                        withId(available.withStatus(EntitlementRecord.Status.UNDER_REVIEW), "r3"),
                        withId(available, "r4")), known.checkedAt()).toJson());

        List<JourneyViewService.RequestView> requests = service.current(SANDBOX, holder).requests();

        assertThat(requests).extracting(JourneyViewService.RequestView::id,
                        JourneyViewService.RequestView::status,
                        request -> request.notAvailableReason().orElse(null))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("r1", JourneyViewService.RequestStatus.NOT_AVAILABLE,
                                "identity_mismatch"),
                        org.assertj.core.groups.Tuple.tuple("r2", JourneyViewService.RequestStatus.NOT_AVAILABLE,
                                "identifier_mismatch"),
                        org.assertj.core.groups.Tuple.tuple("r3", JourneyViewService.RequestStatus.NOT_AVAILABLE,
                                "under_review"),
                        org.assertj.core.groups.Tuple.tuple("r4", JourneyViewService.RequestStatus.AVAILABLE, null));
        assertThat(requests.getFirst().attestation()).isEmpty();
        assertThat(requests.getFirst().requestedBy()).contains("Hospital");
        assertThat(requests.getFirst().requestedAt()).contains(NOW);
        assertThat(requests.get(2).attestation()).isEmpty();
        assertThat(requests.get(3).attestation()).isPresent();
    }

    @Test
    @DisplayName("AC-05 — a request with a process is in_offer, with its offer and onboarding address and still its data")
    void current_requestWithProcessAndOffer_isInOfferWithItsOffer() {
        HolderRef holder = confirmed();
        process(holder, "r1", null);
        offer(holder, "r1", NOW.plusSeconds(600), ONBOARDING_URL, NOW.plusSeconds(540));

        JourneyViewService.RequestView request = service.current(SANDBOX, holder).requests().getFirst();

        assertThat(request.status()).isEqualTo(JourneyViewService.RequestStatus.IN_OFFER);
        IssuanceService.Offer offer = request.offer().orElseThrow();
        assertThat(offer.credentialOfferUri()).isEqualTo(OFFER_URI);
        assertThat(offer.expiresAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(offer.onboardingUrl()).isEqualTo(ONBOARDING_URL);
        assertThat(offer.onboardingExpiresAt()).isEqualTo(NOW.plusSeconds(540));
        assertThat(request.attestation()).isPresent();
    }

    @Test
    @DisplayName("AC-05 — the offer of one request is never shown with another")
    void current_twoRequests_eachShowsItsOwnOffer() {
        HolderRef holder = confirmed();
        EntitlementRecord known = entitlementOf(holder);
        sessions.write(SANDBOX, journeyOf(holder), Section.ENTITLEMENT, new EntitlementRecord(known.identifierType(),
                known.email(), List.of(known.requests().getFirst(), withId(known.requests().getFirst(), "r2")),
                known.checkedAt()).toJson());
        process(holder, "r1", null);
        offer(holder, "r1", NOW.plusSeconds(600), ONBOARDING_URL, NOW.plusSeconds(540));

        List<JourneyViewService.RequestView> requests = service.current(SANDBOX, holder).requests();

        assertThat(requests.get(0).offer()).isPresent();
        assertThat(requests.get(1).status()).isEqualTo(JourneyViewService.RequestStatus.AVAILABLE);
        assertThat(requests.get(1).offer()).isEmpty();
    }

    @Test
    @DisplayName("AC-05 — a saved request shows neither its data nor an offer")
    void current_savedRequest_isSavedWithoutDataNorOffer() {
        HolderRef holder = confirmed();
        process(holder, "r1", NOW);
        offer(holder, "r1", NOW.plusSeconds(600), ONBOARDING_URL, NOW.plusSeconds(540));

        JourneyViewService.RequestView request = service.current(SANDBOX, holder).requests().getFirst();

        assertThat(request.status()).isEqualTo(JourneyViewService.RequestStatus.SAVED);
        assertThat(request.attestation()).isEmpty();
        assertThat(request.offer()).isEmpty();
    }

    @Test
    @DisplayName("AC-17 — an expired offer is still shown, so the interface shows the code as expired")
    void current_offerExpired_isStillShown() {
        HolderRef holder = confirmed();
        process(holder, "r1", null);
        offer(holder, "r1", NOW.minusSeconds(60), ONBOARDING_URL, NOW.minusSeconds(120));

        assertThat(service.current(SANDBOX, holder).requests().getFirst().offer()).isPresent();
    }

    @Test
    @DisplayName("AC-17 — a tenant without onboarding tickets gets the offer without onboarding address")
    void current_tenantWithoutTickets_offerWithoutOnboarding() {
        config.set(Optional.of(config(false)));
        HolderRef holder = confirmed();
        process(holder, "r1", null);
        offer(holder, "r1", NOW.plusSeconds(600), null, null);

        IssuanceService.Offer offer = service.current(SANDBOX, holder).requests().getFirst().offer().orElseThrow();

        assertThat(offer.onboardingUrl()).isNull();
        assertThat(offer.onboardingExpiresAt()).isNull();
    }

    @Test
    @DisplayName("EC-10 — in a tenant with tickets, an offer stored without its onboarding address is not shown")
    void current_tenantWithTickets_offerWithoutOnboarding_isNotShown() {
        HolderRef holder = confirmed();
        process(holder, "r1", null);
        offer(holder, "r1", NOW.plusSeconds(600), null, null);

        assertThat(service.current(SANDBOX, holder).requests().getFirst().offer()).isEmpty();
        assertThat(messages()).anyMatch(message -> message.contains("without its onboarding address"));
    }

    @Test
    @DisplayName("EC-10 — when the configuration cannot be read, an offer without onboarding address is not shown")
    void current_configUnavailable_offerWithoutOnboarding_isNotShown() {
        HolderRef holder = confirmed();
        process(holder, "r1", null);
        offer(holder, "r1", NOW.plusSeconds(600), null, null);
        config.set(Optional.empty());

        assertThat(service.current(SANDBOX, holder).requests().getFirst().offer()).isEmpty();
    }

    @Test
    @DisplayName("ES-07 — a request with its process and no offer yet (the ticket failed) is in_offer without offer")
    void current_processWithoutOffer_isInOfferWithoutOffer() {
        HolderRef holder = confirmed();
        process(holder, "r1", null);
        sessions.write(SANDBOX, journeyOf(holder), Section.ISSUANCE, IssuanceRecord.empty()
                .with("r1", IssuanceRecord.Entry.withKey(UUID.randomUUID(), HOLDER_EMAIL)).toJson());

        JourneyViewService.RequestView request = service.current(SANDBOX, holder).requests().getFirst();

        assertThat(request.status()).isEqualTo(JourneyViewService.RequestStatus.IN_OFFER);
        assertThat(request.offer()).isEmpty();
    }

    @Test
    @DisplayName("EC-11 — an issuance section of the previous shape is left out with a warning, without personal data")
    void current_issuanceSectionInThePreviousShape_isLeftOut() {
        HolderRef holder = confirmed();
        process(holder, "r1", null);
        sessions.write(SANDBOX, journeyOf(holder), Section.ISSUANCE,
                "{\"idempotency_key\":\"" + UUID.randomUUID() + "\",\"credential_offer_uri\":\"" + OFFER_URI
                        + "\",\"offer_expires_at\":\"2030-01-01T10:10:00Z\"}");

        JourneyViewService.RequestView request = service.current(SANDBOX, holder).requests().getFirst();

        assertThat(request.offer()).isEmpty();
        assertThat(request.attestation()).isPresent();
        assertThat(messages()).anyMatch(message -> message.contains("ISSUANCE") && message.contains("invalid"));
        assertThat(messages()).noneMatch(message -> message.contains(OFFER_URI) || message.contains(HOLDER_NAME));
    }

    @Test
    @DisplayName("EC-11 — an entitlement section of the previous shape is left out with a warning")
    void current_entitlementInThePreviousShape_isLeftOutWithAWarning() {
        HolderRef holder = confirmed();
        sessions.write(SANDBOX, journeyOf(holder), Section.ENTITLEMENT, "{\"result\":\"confirmed\",\"claims\":{},"
                + "\"preview\":{\"type_name\":\"DoctorID\",\"fields\":[]},\"checked_at\":\"2026-10-28T10:00:00Z\"}");

        JourneyViewService.View view = service.current(SANDBOX, holder);

        assertThat(view.requests()).isEmpty();
        assertThat(messages()).anyMatch(message -> message.contains("ENTITLEMENT") && message.contains("invalid at requests"));
    }

    @Test
    @DisplayName("EC-11 — a section that cannot be decrypted is left out with a warning")
    void current_undecryptableSection_isLeftOut() {
        HolderRef holder = confirmed();
        undecryptable.set(Section.ENTITLEMENT);

        JourneyViewService.View view = service.current(SANDBOX, holder);

        assertThat(view.requests()).isEmpty();
        assertThat(messages()).anyMatch(message -> message.contains("ENTITLEMENT") && message.contains("cannot be read"));
    }

    @Test
    @DisplayName("AC-25 — a completed journey that is still active is served with its requests")
    void current_completedAndActiveJourney_isServed() {
        HolderRef holder = confirmed();
        process(holder, "r1", null);
        offer(holder, "r1", NOW.plusSeconds(600), ONBOARDING_URL, NOW.plusSeconds(540));
        Journey journey = journeys.findById(SANDBOX, journeyOf(holder)).orElseThrow();
        journey.complete(NOW);
        journeys.update(SANDBOX, journey, JourneyState.IN_PROGRESS);

        JourneyViewService.View view = service.current(SANDBOX, holder);

        assertThat(view.journey().state()).isEqualTo(JourneyState.COMPLETED);
        assertThat(view.requests().getFirst().status()).isEqualTo(JourneyViewService.RequestStatus.IN_OFFER);
    }

    @Test
    @DisplayName("EC-12 — a withdrawn journey still answers journey_closed, before any section is read")
    void current_withdrawn_isClosed() {
        HolderRef holder = confirmed();
        journeyService.cancel(SANDBOX, holder);

        assertThatThrownBy(() -> service.current(SANDBOX, holder)).isInstanceOf(JourneyClosedException.class);
    }

    @Test
    @DisplayName("AC-11 — the text form of the view carries no personal data")
    void view_toString_hasNoPersonalData() {
        HolderRef holder = confirmed();
        process(holder, "r1", null);
        offer(holder, "r1", NOW.plusSeconds(600), ONBOARDING_URL, NOW.plusSeconds(540));

        String text = service.current(SANDBOX, holder).requests().toString();

        assertThat(text).doesNotContain(OFFER_URI, ONBOARDING_URL, HOLDER_NAME, HOLDER_EMAIL, "SIM-1");
    }

    private HolderRef start() {
        HolderRef holder = Holders.next();
        journeyService.open(SANDBOX, holder, new HolderIdentity("Lucia", "Prueba Sintetica",
                new PersonalIdentifier(IdentifierType.COLEGIADO, "280000001"), null), null);
        return holder;
    }

    private UUID journeyOf(HolderRef holder) {
        return sessions.journeyOf(SANDBOX, holder).orElseThrow();
    }

    private HolderRef confirmed() {
        HolderRef holder = start();
        journeyService.reach(SANDBOX, holder, JourneyProgress.Step.IDENTIFIED);
        EntitlementRecord.Preview preview = new EntitlementRecord.Preview("DoctorID", List.of(
                new EntitlementRecord.Field("Nombre y apellidos", HOLDER_NAME),
                new EntitlementRecord.Field("Número de colegiado", "280000001")));
        sessions.write(SANDBOX, journeyOf(holder), Section.ENTITLEMENT, EntitlementRecords.available(
                IdentifierType.COLEGIADO, Map.of("license_number", "280000001"), preview, HOLDER_EMAIL, NOW).toJson());
        journeyService.reach(SANDBOX, holder, JourneyProgress.Step.ENTITLEMENT_CONFIRMED);
        return holder;
    }

    private static EntitlementRecord.Request withId(EntitlementRecord.Request request, String id) {
        return new EntitlementRecord.Request(id, "SIM-" + id, request.status(), request.mismatchedFields(),
                request.renewal(), request.requestedBy(), request.requestedAt(), request.displayIdentifier(),
                request.organization(), request.claims(), request.preview());
    }

    private EntitlementRecord entitlementOf(HolderRef holder) {
        return EntitlementRecord.fromJson(sessions.read(SANDBOX, journeyOf(holder), Section.ENTITLEMENT).orElseThrow());
    }

    /** The request has its issuance process, and was saved at {@code savedAt} if it is not null. */
    private void process(HolderRef holder, String requestId, Instant savedAt) {
        issuances.insert(SANDBOX, new JourneyIssuance(journeyOf(holder), requestId, UUID.randomUUID(), NOW, savedAt));
    }

    private void offer(HolderRef holder, String requestId, Instant expiresAt, String onboardingUrl,
                       Instant onboardingExpiresAt) {
        sessions.write(SANDBOX, journeyOf(holder), Section.ISSUANCE, IssuanceRecord.empty().with(requestId,
                new IssuanceRecord.Entry(UUID.randomUUID(), HOLDER_EMAIL, OFFER_URI, expiresAt, onboardingUrl,
                        onboardingExpiresAt)).toJson());
    }

    private List<String> messages() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static PortalConfig config(boolean onboarding) {
        Onboarding settings = onboarding ? new Onboarding(true, "http://wallet-unit") : Onboarding.DISABLED;
        return new PortalConfig(Map.of(), new LegalLinks(null, null, null, null), new Support(null, null, null, null),
                "https://sandbox.127.0.0.1.nip.io:4443/wallet",
                new ServiceSettings("doctorid.sd.1", Map.of(), settings, null, Map.of()));
    }
}
