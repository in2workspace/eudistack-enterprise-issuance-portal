package es.in2.issuanceportal.journey.application;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import es.in2.issuanceportal.MutableClock;
import es.in2.issuanceportal.journey.InMemoryJourneyRepository;
import es.in2.issuanceportal.journey.RecordingJourneyAuditTrail;
import es.in2.issuanceportal.journey.api.JourneyProgress;
import es.in2.issuanceportal.journey.domain.JourneyClosedException;
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
    private final JourneyViewService service =
            new JourneyViewService(journeyService, sessions, new PortalConfigService(tenant -> config.get()));

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
    @DisplayName("EC-12 — a journey just started has neither attestation nor offer")
    void current_started_hasNeitherAttestationNorOffer() {
        HolderRef holder = start();

        JourneyViewService.View view = service.current(SANDBOX, holder);

        assertThat(view.attestation()).isEmpty();
        assertThat(view.offer()).isEmpty();
    }

    @Test
    @DisplayName("AC-16 — with the entitlement confirmed, the view carries the preview the authentic source kept")
    void current_entitlementConfirmed_carriesPreview() {
        HolderRef holder = confirmed();

        JourneyViewService.View view = service.current(SANDBOX, holder);

        JourneyViewService.Attestation preview = view.attestation().orElseThrow();
        assertThat(preview.typeName()).isEqualTo("DoctorID");
        assertThat(preview.fields()).extracting(EntitlementRecord.Field::label, EntitlementRecord.Field::value)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Nombre y apellidos", HOLDER_NAME),
                        org.assertj.core.groups.Tuple.tuple("Número de colegiado", "280000001"));
        assertThat(preview.email()).isEqualTo(HOLDER_EMAIL);
        assertThat(preview.emailRequired()).isFalse();
        assertThat(view.offer()).isEmpty();
    }

    @Test
    @DisplayName("EUD-359 1b — current_entitlementWithoutEmail_requiresOne")
    void current_entitlementWithoutEmail_requiresOne() {
        HolderRef holder = confirmed();
        EntitlementRecord known = EntitlementRecord.fromJson(
                sessions.read(SANDBOX, journeyOf(holder), Section.ENTITLEMENT).orElseThrow());
        sessions.write(SANDBOX, journeyOf(holder), Section.ENTITLEMENT, new EntitlementRecord(known.identifierType(),
                null, known.requests(), known.checkedAt()).toJson());

        JourneyViewService.Attestation preview = service.current(SANDBOX, holder).attestation().orElseThrow();

        assertThat(preview.email()).isNull();
        assertThat(preview.emailRequired()).isTrue();
    }

    @Test
    @DisplayName("EUD-359 1b — current_severalRequests_showsThePreviewOfTheFirstAvailableOne")
    void current_severalRequests_showsThePreviewOfTheFirstAvailableOne() {
        HolderRef holder = confirmed();
        EntitlementRecord known = EntitlementRecord.fromJson(
                sessions.read(SANDBOX, journeyOf(holder), Section.ENTITLEMENT).orElseThrow());
        EntitlementRecord.Request mismatch = new EntitlementRecord.Request("r1", "SIM-0", EntitlementRecord.Status
                .IDENTITY_MISMATCH, List.of("given_name"), false, null, null, null, null, null, null);
        sessions.write(SANDBOX, journeyOf(holder), Section.ENTITLEMENT, new EntitlementRecord(known.identifierType(),
                known.email(), List.of(mismatch, known.requests().getFirst()), known.checkedAt()).toJson());

        JourneyViewService.Attestation preview = service.current(SANDBOX, holder).attestation().orElseThrow();

        assertThat(preview.fields()).extracting(EntitlementRecord.Field::value).contains("280000001");
    }

    @Test
    @DisplayName("EUD-359 1b — current_entitlementInThePreviousShape_isLeftOutWithAWarning")
    void current_entitlementInThePreviousShape_isLeftOutWithAWarning() {
        HolderRef holder = confirmed();
        sessions.write(SANDBOX, journeyOf(holder), Section.ENTITLEMENT, "{\"result\":\"confirmed\",\"claims\":{},"
                + "\"preview\":{\"type_name\":\"DoctorID\",\"fields\":[]},\"checked_at\":\"2026-10-28T10:00:00Z\"}");

        JourneyViewService.View view = service.current(SANDBOX, holder);

        assertThat(view.attestation()).isEmpty();
        assertThat(messages()).anyMatch(message -> message.contains("ENTITLEMENT") && message.contains("invalid at requests"));
    }

    @Test
    @DisplayName("AC-17 — with the offer obtained, the view carries it with its onboarding address, and still the preview")
    void current_offerObtained_carriesOfferAndPreview() {
        HolderRef holder = confirmed();
        sessions.write(SANDBOX, journeyOf(holder), Section.ISSUANCE, new IssuanceRecord(UUID.randomUUID(), null, OFFER_URI,
                NOW.plusSeconds(600), ONBOARDING_URL, NOW.plusSeconds(540)).toJson());

        JourneyViewService.View view = service.current(SANDBOX, holder);

        IssuanceService.Offer offer = view.offer().orElseThrow();
        assertThat(offer.credentialOfferUri()).isEqualTo(OFFER_URI);
        assertThat(offer.expiresAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(offer.onboardingUrl()).isEqualTo(ONBOARDING_URL);
        assertThat(offer.onboardingExpiresAt()).isEqualTo(NOW.plusSeconds(540));
        assertThat(view.attestation()).isPresent();
    }

    @Test
    @DisplayName("AC-17 — an expired offer is still shown, so the interface shows the code as expired")
    void current_offerExpired_isStillShown() {
        HolderRef holder = confirmed();
        sessions.write(SANDBOX, journeyOf(holder), Section.ISSUANCE, new IssuanceRecord(UUID.randomUUID(), null, OFFER_URI,
                NOW.minusSeconds(60), ONBOARDING_URL, NOW.minusSeconds(120)).toJson());

        assertThat(service.current(SANDBOX, holder).offer()).isPresent();
    }

    @Test
    @DisplayName("AC-17 — a tenant without onboarding tickets gets the offer without onboarding address")
    void current_tenantWithoutTickets_offerWithoutOnboarding() {
        config.set(Optional.of(config(false)));
        HolderRef holder = confirmed();
        sessions.write(SANDBOX, journeyOf(holder), Section.ISSUANCE, new IssuanceRecord(UUID.randomUUID(), null, OFFER_URI,
                NOW.plusSeconds(600), null, null).toJson());

        IssuanceService.Offer offer = service.current(SANDBOX, holder).offer().orElseThrow();

        assertThat(offer.onboardingUrl()).isNull();
        assertThat(offer.onboardingExpiresAt()).isNull();
    }

    @Test
    @DisplayName("EC-10 — in a tenant with tickets, an offer stored without its onboarding address is not shown")
    void current_tenantWithTickets_offerWithoutOnboarding_isNotShown() {
        HolderRef holder = confirmed();
        sessions.write(SANDBOX, journeyOf(holder), Section.ISSUANCE, new IssuanceRecord(UUID.randomUUID(), null, OFFER_URI,
                NOW.plusSeconds(600), null, null).toJson());

        assertThat(service.current(SANDBOX, holder).offer()).isEmpty();
        assertThat(messages()).anyMatch(message -> message.contains("without its onboarding address"));
    }

    @Test
    @DisplayName("EC-10 — when the configuration cannot be read, an offer without onboarding address is not shown")
    void current_configUnavailable_offerWithoutOnboarding_isNotShown() {
        HolderRef holder = confirmed();
        sessions.write(SANDBOX, journeyOf(holder), Section.ISSUANCE, new IssuanceRecord(UUID.randomUUID(), null, OFFER_URI,
                NOW.plusSeconds(600), null, null).toJson());
        config.set(Optional.empty());

        assertThat(service.current(SANDBOX, holder).offer()).isEmpty();
    }

    @Test
    @DisplayName("issuance section with only the idempotency key (request in flight) shows no offer")
    void current_issuanceKeyOnly_noOffer() {
        HolderRef holder = confirmed();
        sessions.write(SANDBOX, journeyOf(holder), Section.ISSUANCE, IssuanceRecord.withKey(UUID.randomUUID(), null).toJson());

        assertThat(service.current(SANDBOX, holder).offer()).isEmpty();
    }

    @Test
    @DisplayName("EC-11 — a section that does not have the contract's shape is left out with a warning, without personal data")
    void current_invalidSection_isLeftOut() {
        HolderRef holder = confirmed();
        sessions.write(SANDBOX, journeyOf(holder), Section.ISSUANCE, "{\"credential_offer_uri\":\"" + OFFER_URI + "\"}");

        JourneyViewService.View view = service.current(SANDBOX, holder);

        assertThat(view.offer()).isEmpty();
        assertThat(view.attestation()).isPresent();
        assertThat(messages()).anyMatch(message -> message.contains("ISSUANCE") && message.contains("invalid"));
        assertThat(messages()).noneMatch(message -> message.contains(OFFER_URI) || message.contains(HOLDER_NAME));
    }

    @Test
    @DisplayName("EC-11 — a section that cannot be decrypted is left out with a warning")
    void current_undecryptableSection_isLeftOut() {
        HolderRef holder = confirmed();
        undecryptable.set(Section.ENTITLEMENT);

        JourneyViewService.View view = service.current(SANDBOX, holder);

        assertThat(view.attestation()).isEmpty();
        assertThat(messages()).anyMatch(message -> message.contains("ENTITLEMENT") && message.contains("cannot be read"));
    }

    @Test
    @DisplayName("EC-12 — a withdrawn journey still answers journey_closed, before any section is read")
    void current_withdrawn_isClosed() {
        HolderRef holder = confirmed();
        journeyService.cancel(SANDBOX, holder);

        assertThatThrownBy(() -> service.current(SANDBOX, holder)).isInstanceOf(JourneyClosedException.class);
    }

    @Test
    @DisplayName("AC-15 — the text form of the view carries no personal data")
    void view_toString_hasNoPersonalData() {
        HolderRef holder = confirmed();
        sessions.write(SANDBOX, journeyOf(holder), Section.ISSUANCE, new IssuanceRecord(UUID.randomUUID(), null, OFFER_URI,
                NOW.plusSeconds(600), ONBOARDING_URL, NOW.plusSeconds(540)).toJson());

        String text = service.current(SANDBOX, holder).toString();

        assertThat(text).doesNotContain(OFFER_URI, ONBOARDING_URL, HOLDER_NAME, HOLDER_EMAIL);
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
