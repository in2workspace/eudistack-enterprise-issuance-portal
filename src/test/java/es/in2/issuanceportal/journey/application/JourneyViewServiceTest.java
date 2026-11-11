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
import es.in2.issuanceportal.session.InMemoryJourneySessions;
import es.in2.issuanceportal.session.api.EntitlementRecord;
import es.in2.issuanceportal.session.api.IdentifierType;
import es.in2.issuanceportal.session.api.IssuanceRecord;
import es.in2.issuanceportal.session.api.Section;
import es.in2.issuanceportal.session.api.SessionId;
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
    private final InMemoryJourneySessions stored = new InMemoryJourneySessions();
    private final AtomicReference<Section> undecryptable = new AtomicReference<>();
    private final InMemoryJourneySessions sessions = new InMemoryJourneySessions() {
        @Override
        public void open(TenantId tenant, SessionId session, UUID journeyId) {
            stored.open(tenant, session, journeyId);
        }

        @Override
        public Optional<UUID> journeyOf(TenantId tenant, SessionId session) {
            return stored.journeyOf(tenant, session);
        }

        @Override
        public Optional<String> read(TenantId tenant, SessionId session, Section section) {
            if (section == undecryptable.get()) {
                throw new IllegalArgumentException("Cannot process the personal data envelope");
            }
            return stored.read(tenant, session, section);
        }

        @Override
        public void write(TenantId tenant, SessionId session, Section section, String content) {
            stored.write(tenant, session, section, content);
        }

        @Override
        public void close(TenantId tenant, UUID journeyId) {
            stored.close(tenant, journeyId);
        }

        @Override
        public SessionId rotate(TenantId tenant, SessionId session) {
            return stored.rotate(tenant, session);
        }
    };
    private final InMemoryJourneyRepository journeys = new InMemoryJourneyRepository(stored);
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
        SessionId session = start();

        JourneyViewService.View view = service.current(SANDBOX, session);

        assertThat(view.attestation()).isEmpty();
        assertThat(view.offer()).isEmpty();
    }

    @Test
    @DisplayName("AC-16 — with the entitlement confirmed, the view carries the preview the authentic source kept")
    void current_entitlementConfirmed_carriesPreview() {
        SessionId session = confirmed();

        JourneyViewService.View view = service.current(SANDBOX, session);

        EntitlementRecord.Preview preview = view.attestation().orElseThrow();
        assertThat(preview.typeName()).isEqualTo("DoctorID");
        assertThat(preview.fields()).extracting(EntitlementRecord.Field::label, EntitlementRecord.Field::value)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Nombre y apellidos", HOLDER_NAME),
                        org.assertj.core.groups.Tuple.tuple("Número de colegiado", "280000001"));
        assertThat(preview.email()).isEqualTo(HOLDER_EMAIL);
        assertThat(preview.emailRequired()).isTrue();
        assertThat(view.offer()).isEmpty();
    }

    @Test
    @DisplayName("AC-17 — with the offer obtained, the view carries it with its onboarding address, and still the preview")
    void current_offerObtained_carriesOfferAndPreview() {
        SessionId session = confirmed();
        sessions.write(SANDBOX, session, Section.ISSUANCE, new IssuanceRecord(UUID.randomUUID(), null, OFFER_URI,
                NOW.plusSeconds(600), ONBOARDING_URL, NOW.plusSeconds(540)).toJson());

        JourneyViewService.View view = service.current(SANDBOX, session);

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
        SessionId session = confirmed();
        sessions.write(SANDBOX, session, Section.ISSUANCE, new IssuanceRecord(UUID.randomUUID(), null, OFFER_URI,
                NOW.minusSeconds(60), ONBOARDING_URL, NOW.minusSeconds(120)).toJson());

        assertThat(service.current(SANDBOX, session).offer()).isPresent();
    }

    @Test
    @DisplayName("AC-17 — a tenant without onboarding tickets gets the offer without onboarding address")
    void current_tenantWithoutTickets_offerWithoutOnboarding() {
        config.set(Optional.of(config(false)));
        SessionId session = confirmed();
        sessions.write(SANDBOX, session, Section.ISSUANCE, new IssuanceRecord(UUID.randomUUID(), null, OFFER_URI,
                NOW.plusSeconds(600), null, null).toJson());

        IssuanceService.Offer offer = service.current(SANDBOX, session).offer().orElseThrow();

        assertThat(offer.onboardingUrl()).isNull();
        assertThat(offer.onboardingExpiresAt()).isNull();
    }

    @Test
    @DisplayName("EC-10 — in a tenant with tickets, an offer stored without its onboarding address is not shown")
    void current_tenantWithTickets_offerWithoutOnboarding_isNotShown() {
        SessionId session = confirmed();
        sessions.write(SANDBOX, session, Section.ISSUANCE, new IssuanceRecord(UUID.randomUUID(), null, OFFER_URI,
                NOW.plusSeconds(600), null, null).toJson());

        assertThat(service.current(SANDBOX, session).offer()).isEmpty();
        assertThat(messages()).anyMatch(message -> message.contains("without its onboarding address"));
    }

    @Test
    @DisplayName("EC-10 — when the configuration cannot be read, an offer without onboarding address is not shown")
    void current_configUnavailable_offerWithoutOnboarding_isNotShown() {
        SessionId session = confirmed();
        sessions.write(SANDBOX, session, Section.ISSUANCE, new IssuanceRecord(UUID.randomUUID(), null, OFFER_URI,
                NOW.plusSeconds(600), null, null).toJson());
        config.set(Optional.empty());

        assertThat(service.current(SANDBOX, session).offer()).isEmpty();
    }

    @Test
    @DisplayName("issuance section with only the idempotency key (request in flight) shows no offer")
    void current_issuanceKeyOnly_noOffer() {
        SessionId session = confirmed();
        sessions.write(SANDBOX, session, Section.ISSUANCE, IssuanceRecord.withKey(UUID.randomUUID(), null).toJson());

        assertThat(service.current(SANDBOX, session).offer()).isEmpty();
    }

    @Test
    @DisplayName("EC-11 — a section that does not have the contract's shape is left out with a warning, without personal data")
    void current_invalidSection_isLeftOut() {
        SessionId session = confirmed();
        sessions.write(SANDBOX, session, Section.ISSUANCE, "{\"credential_offer_uri\":\"" + OFFER_URI + "\"}");

        JourneyViewService.View view = service.current(SANDBOX, session);

        assertThat(view.offer()).isEmpty();
        assertThat(view.attestation()).isPresent();
        assertThat(messages()).anyMatch(message -> message.contains("ISSUANCE") && message.contains("invalid"));
        assertThat(messages()).noneMatch(message -> message.contains(OFFER_URI) || message.contains(HOLDER_NAME));
    }

    @Test
    @DisplayName("EC-11 — a section that cannot be decrypted is left out with a warning")
    void current_undecryptableSection_isLeftOut() {
        SessionId session = confirmed();
        undecryptable.set(Section.ENTITLEMENT);

        JourneyViewService.View view = service.current(SANDBOX, session);

        assertThat(view.attestation()).isEmpty();
        assertThat(messages()).anyMatch(message -> message.contains("ENTITLEMENT") && message.contains("cannot be read"));
    }

    @Test
    @DisplayName("EC-12 — a withdrawn journey still answers journey_closed, before any section is read")
    void current_withdrawn_isClosed() {
        SessionId session = confirmed();
        journeyService.cancel(SANDBOX, session);

        assertThatThrownBy(() -> service.current(SANDBOX, session)).isInstanceOf(JourneyClosedException.class);
    }

    @Test
    @DisplayName("AC-15 — the text form of the view carries no personal data")
    void view_toString_hasNoPersonalData() {
        SessionId session = confirmed();
        sessions.write(SANDBOX, session, Section.ISSUANCE, new IssuanceRecord(UUID.randomUUID(), null, OFFER_URI,
                NOW.plusSeconds(600), ONBOARDING_URL, NOW.plusSeconds(540)).toJson());

        String text = service.current(SANDBOX, session).toString();

        assertThat(text).doesNotContain(OFFER_URI, ONBOARDING_URL, HOLDER_NAME, HOLDER_EMAIL);
    }

    private SessionId start() {
        return journeyService.start(SANDBOX, Optional.empty()).session();
    }

    private SessionId confirmed() {
        SessionId session = start();
        journeyService.reach(SANDBOX, session, JourneyProgress.Step.IDENTIFIED);
        EntitlementRecord.Preview preview = new EntitlementRecord.Preview("DoctorID", List.of(
                new EntitlementRecord.Field("Nombre y apellidos", HOLDER_NAME),
                new EntitlementRecord.Field("Número de colegiado", "280000001")), HOLDER_EMAIL, true);
        sessions.write(SANDBOX, session, Section.ENTITLEMENT, new EntitlementRecord(IdentifierType.COLEGIADO,
                Map.of("license_number", "280000001"), preview, NOW).toJson());
        journeyService.reach(SANDBOX, session, JourneyProgress.Step.ENTITLEMENT_CONFIRMED);
        return session;
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
