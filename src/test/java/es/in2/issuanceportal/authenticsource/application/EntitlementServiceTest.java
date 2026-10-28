package es.in2.issuanceportal.authenticsource.application;

import es.in2.issuanceportal.MutableClock;
import es.in2.issuanceportal.authenticsource.StubAdapter;
import es.in2.issuanceportal.authenticsource.domain.AttestationPreview;
import es.in2.issuanceportal.authenticsource.domain.IdentifierType;
import es.in2.issuanceportal.authenticsource.domain.LookupResult;
import es.in2.issuanceportal.authenticsource.domain.NotConfirmedReason;
import es.in2.issuanceportal.authenticsource.domain.PersonalIdentifier;
import es.in2.issuanceportal.authenticsource.domain.SourceRecord;
import es.in2.issuanceportal.authenticsource.domain.config.AttributeMapping;
import es.in2.issuanceportal.authenticsource.domain.config.AuthenticSourceConfig;
import es.in2.issuanceportal.authenticsource.domain.config.SourceSettings;
import es.in2.issuanceportal.journey.InMemoryJourneyRepository;
import es.in2.issuanceportal.journey.RecordingJourneyAuditTrail;
import es.in2.issuanceportal.journey.application.JourneyService;
import es.in2.issuanceportal.journey.domain.Journey;
import es.in2.issuanceportal.journey.domain.JourneyClosedException;
import es.in2.issuanceportal.journey.domain.JourneyState;
import es.in2.issuanceportal.journey.domain.JourneyStep;
import es.in2.issuanceportal.journey.domain.WithdrawalReason;
import es.in2.issuanceportal.session.InMemoryJourneySessions;
import es.in2.issuanceportal.session.api.EntitlementRecord;
import es.in2.issuanceportal.session.api.HolderIdentity;
import es.in2.issuanceportal.session.api.Section;
import es.in2.issuanceportal.session.api.SessionId;
import es.in2.issuanceportal.shared.error.ProblemCode;
import es.in2.issuanceportal.shared.error.ProblemException;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntitlementServiceTest {

    private static final TenantId CGCOM = new TenantId("cgcom");
    private static final String DOCTORID = "doctorid.sd.1";
    private static final PersonalIdentifier ACTIVE_DNI = new PersonalIdentifier(IdentifierType.DNI, "00000001R");
    private static final PersonalIdentifier ACTIVE_COLEGIADO =
            new PersonalIdentifier(IdentifierType.COLEGIADO, "280000001");
    private static final List<AttributeMapping> MAPPING = List.of(
            new AttributeMapping("registration_number", "personal_administrative_number", "Número de colegiado"),
            new AttributeMapping("medical_boards", "medical_board", "Colegio"),
            new AttributeMapping("entitled_to_practise", "legally_entitled", "Habilitado para ejercer"),
            new AttributeMapping("email", "contact_email", null));

    private final InMemoryJourneySessions sessions = new InMemoryJourneySessions();
    private final InMemoryJourneyRepository journeys = new InMemoryJourneyRepository(sessions);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-28T10:00:00Z"));
    private final JourneyService journey = new JourneyService(journeys, sessions, new RecordingJourneyAuditTrail(),
            TransactionOperations.withoutTransaction(), TransactionOperations.withoutTransaction(), clock);
    private final StubAdapter adapter = StubAdapter.searchingByBoth("simulated").answering(EntitlementServiceTest::source);
    private final Map<TenantId, AuthenticSourceConfig> rows = new HashMap<>();
    private final EntitlementService service = new EntitlementService(journey, journey, journey, sessions,
            new AuthenticSourceConfigs(tenant -> Optional.ofNullable(rows.get(tenant)),
                    new AdapterRegistry(List.of(adapter))),
            TransactionOperations.withoutTransaction(), clock);

    /** Two people: the active one, findable by DNI and by registration number; one deregistered. */
    private static LookupResult source(PersonalIdentifier searched) {
        if (searched.sameAs(ACTIVE_DNI) || searched.sameAs(ACTIVE_COLEGIADO)) {
            return LookupResult.found(new SourceRecord(List.of(ACTIVE_DNI, ACTIVE_COLEGIADO), true, Map.of(
                    "registration_number", "280000001",
                    "medical_boards", List.of("Madrid"),
                    "entitled_to_practise", true)));
        }
        PersonalIdentifier deregistered = new PersonalIdentifier(IdentifierType.DNI, "00000002W");
        if (searched.sameAs(deregistered)) {
            return LookupResult.found(new SourceRecord(List.of(deregistered,
                    new PersonalIdentifier(IdentifierType.COLEGIADO, "280000002")), false, Map.of()));
        }
        return LookupResult.notFound();
    }

    @AfterEach
    void closeService() {
        service.close();
    }

    private void configure(Duration timeout) {
        rows.put(CGCOM, new AuthenticSourceConfig(DOCTORID, Set.of(IdentifierType.COLEGIADO), Map.of(DOCTORID,
                new SourceSettings("DoctorID", "simulated", null, null, timeout,
                        Set.of(IdentifierType.DNI, IdentifierType.COLEGIADO), MAPPING, "email", null))));
    }

    private SessionId journeyWith(es.in2.issuanceportal.session.api.IdentifierType type, String value,
                                  String email) {
        configure(null);
        SessionId session = journey.start(CGCOM, Optional.empty()).session();
        sessions.write(CGCOM, session, Section.IDENTITY, new HolderIdentity("Lucía", "Prueba Sintética",
                new es.in2.issuanceportal.session.api.PersonalIdentifier(type, value), email).toJson());
        return session;
    }

    private SessionId journeyWithDni(String dni) {
        return journeyWith(es.in2.issuanceportal.session.api.IdentifierType.DNI, dni, null);
    }

    private Journey stored(UUID id) {
        return journeys.findById(CGCOM, id).orElseThrow();
    }

    private UUID journeyId(SessionId session) {
        return sessions.journeyOf(CGCOM, session).orElseThrow();
    }

    @Test
    @DisplayName("AC-03, AC-04 — check_activeByDni_confirmsWritesTheEntitlementAndReachesTheStep")
    void check_activeByDni_confirmsWritesTheEntitlementAndReachesTheStep() {
        SessionId session = journeyWithDni("00000001R");
        UUID id = journeyId(session);

        EntitlementService.Outcome outcome = service.check(CGCOM, session);

        assertThat(adapter.searches()).containsExactly(ACTIVE_DNI);
        AttestationPreview preview = ((EntitlementService.Outcome.Confirmed) outcome).preview();
        assertThat(preview.typeName()).isEqualTo("DoctorID");
        assertThat(preview.fields()).extracting(AttestationPreview.Field::value)
                .contains("Lucía Prueba Sintética", "280000001", "Madrid", "Sí")
                .doesNotContain("00000001R");
        assertThat(preview.emailRequired()).isTrue();
        EntitlementRecord written = EntitlementRecord.fromJson(
                sessions.read(CGCOM, session, Section.ENTITLEMENT).orElseThrow());
        assertThat(written.identifierTypeIfSearched()).contains(es.in2.issuanceportal.session.api.IdentifierType.DNI);
        assertThat(written.claims()).containsOnlyKeys("personal_administrative_number", "medical_board",
                "legally_entitled");
        assertThat(written.checkedAt()).isEqualTo(clock.instant());
        assertThat(stored(id).step()).isEqualTo(JourneyStep.ENTITLEMENT_CONFIRMED);
    }

    @Test
    @DisplayName("AC-05 — check_doctorIdLogin_searchesByRegistrationNumberOnly")
    void check_doctorIdLogin_searchesByRegistrationNumberOnly() {
        SessionId session = journeyWith(es.in2.issuanceportal.session.api.IdentifierType.COLEGIADO, "280000001", null);

        assertThat(service.check(CGCOM, session)).isInstanceOf(EntitlementService.Outcome.Confirmed.class);
        assertThat(adapter.searches()).containsExactly(ACTIVE_COLEGIADO);
    }

    @ParameterizedTest
    @CsvSource({"dni,00000002W,NOT_ENTITLED", "dni,00000009Y,NOT_FOUND", "colegiado,289999999,NOT_FOUND"})
    @DisplayName("AC-07 — check_deregisteredOrUnknown_isNotConfirmedAndWithdrawsTheJourney")
    void check_deregisteredOrUnknown_isNotConfirmedAndWithdrawsTheJourney(String type, String value,
                                                                         NotConfirmedReason reason) {
        SessionId session = journeyWith(es.in2.issuanceportal.session.api.IdentifierType.fromCode(type).orElseThrow(),
                value, null);
        UUID id = journeyId(session);

        assertThat(service.check(CGCOM, session)).isEqualTo(new EntitlementService.Outcome.NotConfirmed(reason));

        Journey withdrawn = stored(id);
        assertThat(withdrawn.state()).isEqualTo(JourneyState.WITHDRAWN);
        assertThat(withdrawn.withdrawalReason()).contains(WithdrawalReason.NOT_ENTITLED);
        assertThat(sessions.journeyOf(CGCOM, session)).isEmpty();
        assertThatThrownBy(() -> service.check(CGCOM, session)).isInstanceOf(JourneyClosedException.class);
    }

    @Test
    @DisplayName("AC-08 — check_sourceReturnsAnotherPerson_isIdentifierMismatchAndWithdraws")
    void check_sourceReturnsAnotherPerson_isIdentifierMismatchAndWithdraws() {
        adapter.answering(searched -> LookupResult.found(new SourceRecord(
                List.of(new PersonalIdentifier(IdentifierType.DNI, "00000003A")), true, Map.of())));
        SessionId session = journeyWithDni("00000001R");
        UUID id = journeyId(session);

        assertThat(service.check(CGCOM, session))
                .isEqualTo(new EntitlementService.Outcome.NotConfirmed(NotConfirmedReason.IDENTIFIER_MISMATCH));
        assertThat(stored(id).withdrawalReason()).contains(WithdrawalReason.NOT_ENTITLED);
    }

    @Test
    @DisplayName("AC-09 — check_sourceSlowerThanTheTimeout_isUnavailableAndTheJourneyGoesOn")
    void check_sourceSlowerThanTheTimeout_isUnavailableAndTheJourneyGoesOn() {
        SessionId session = journeyWithDni("00000001R");
        configure(Duration.ofSeconds(1));
        adapter.answering(searched -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return source(searched);
        });

        long start = System.nanoTime();
        assertThatThrownBy(() -> service.check(CGCOM, session)).isInstanceOfSatisfying(ProblemException.class, e -> {
            assertThat(e.problem()).isEqualTo(ProblemCode.AUTHENTIC_SOURCE_UNAVAILABLE);
            assertThat(e.retryAfter()).contains(Duration.ofSeconds(30));
        });
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));

        assertThat(sessions.read(CGCOM, session, Section.ENTITLEMENT)).isEmpty();
        assertThat(stored(journeyId(session)).state()).isEqualTo(JourneyState.IN_PROGRESS);
        adapter.answering(EntitlementServiceTest::source);
        assertThat(service.check(CGCOM, session)).isInstanceOf(EntitlementService.Outcome.Confirmed.class);
    }

    @Test
    @DisplayName("ES-01 — check_sourceFails_isUnavailableNeverConfirmed")
    void check_sourceFails_isUnavailableNeverConfirmed() {
        SessionId session = journeyWithDni("00000001R");
        adapter.answering(searched -> {
            throw new IllegalStateException("source said 00000001R is unknown");
        });

        assertThatThrownBy(() -> service.check(CGCOM, session)).isInstanceOfSatisfying(ProblemException.class,
                e -> assertThat(e.problem()).isEqualTo(ProblemCode.AUTHENTIC_SOURCE_UNAVAILABLE));
        assertThat(stored(journeyId(session)).state()).isEqualTo(JourneyState.IN_PROGRESS);
    }

    @Test
    @DisplayName("ES-01 — check_sourceAnswersNothing_isUnavailable")
    void check_sourceAnswersNothing_isUnavailable() {
        SessionId session = journeyWithDni("00000001R");
        adapter.answering(searched -> null);

        assertThatThrownBy(() -> service.check(CGCOM, session)).isInstanceOfSatisfying(ProblemException.class,
                e -> assertThat(e.problem()).isEqualTo(ProblemCode.AUTHENTIC_SOURCE_UNAVAILABLE));
    }

    @Test
    @DisplayName("AC-10 — check_attestationTypeWithoutSource_confirmsWithoutData")
    void check_attestationTypeWithoutSource_confirmsWithoutData() {
        SessionId session = journeyWith(es.in2.issuanceportal.session.api.IdentifierType.DNI, "00000001R",
                "lucia@example.org");
        rows.put(CGCOM, new AuthenticSourceConfig("employee.sd.1", Set.of(), Map.of()));

        EntitlementService.Outcome outcome = service.check(CGCOM, session);

        AttestationPreview preview = ((EntitlementService.Outcome.Confirmed) outcome).preview();
        assertThat(preview.fields()).hasSize(1);
        assertThat(preview.email()).isEqualTo("lucia@example.org");
        assertThat(preview.emailRequired()).isFalse();
        assertThat(adapter.searches()).isEmpty();
        EntitlementRecord written = EntitlementRecord.fromJson(
                sessions.read(CGCOM, session, Section.ENTITLEMENT).orElseThrow());
        assertThat(written.claims()).isEmpty();
        assertThat(written.identifierTypeIfSearched()).isEmpty();
    }

    @Test
    @DisplayName("AC-01 — check_journeyWithoutIdentity_isStepNotAllowed")
    void check_journeyWithoutIdentity_isStepNotAllowed() {
        configure(null);
        SessionId session = journey.start(CGCOM, Optional.empty()).session();

        assertThatThrownBy(() -> service.check(CGCOM, session)).isInstanceOfSatisfying(ProblemException.class,
                e -> assertThat(e.problem()).isEqualTo(ProblemCode.STEP_NOT_ALLOWED));
        assertThat(adapter.searches()).isEmpty();
        assertThat(stored(journeyId(session)).step()).isEqualTo(JourneyStep.STARTED);
    }

    @Test
    @DisplayName("ES-02 — check_identityWithInvalidShape_isStepNotAllowed")
    void check_identityWithInvalidShape_isStepNotAllowed() {
        configure(null);
        SessionId session = journey.start(CGCOM, Optional.empty()).session();
        sessions.write(CGCOM, session, Section.IDENTITY,
                "{\"given_name\":\"L\",\"family_name\":\"P\",\"personal_identifier\":{\"type\":\"nif\",\"value\":\"1\"}}");

        assertThatThrownBy(() -> service.check(CGCOM, session)).isInstanceOfSatisfying(ProblemException.class,
                e -> assertThat(e.problem()).isEqualTo(ProblemCode.STEP_NOT_ALLOWED));
        assertThat(adapter.searches()).isEmpty();
    }

    @Test
    @DisplayName("EC-01 — check_again_replacesTheEntitlement")
    void check_again_replacesTheEntitlement() {
        SessionId session = journeyWithDni("00000001R");
        service.check(CGCOM, session);
        clock.advance(Duration.ofMinutes(1));

        service.check(CGCOM, session);

        EntitlementRecord written = EntitlementRecord.fromJson(
                sessions.read(CGCOM, session, Section.ENTITLEMENT).orElseThrow());
        assertThat(written.checkedAt()).isEqualTo(clock.instant());
        assertThat(stored(journeyId(session)).step()).isEqualTo(JourneyStep.ENTITLEMENT_CONFIRMED);
    }

    @Test
    @DisplayName("EC-04 — check_journeyInactiveForFifteenMinutes_isJourneyClosed")
    void check_journeyInactiveForFifteenMinutes_isJourneyClosed() {
        SessionId session = journeyWithDni("00000001R");
        clock.advance(Duration.ofMinutes(16));

        assertThatThrownBy(() -> service.check(CGCOM, session)).isInstanceOf(JourneyClosedException.class);
        assertThat(adapter.searches()).isEmpty();
    }

    @Test
    @DisplayName("ES-04 — check_withdrawalFails_isAnErrorNeverAResult")
    void check_withdrawalFails_isAnErrorNeverAResult() {
        SessionId session = journeyWithDni("00000002W");
        EntitlementService failing = new EntitlementService(journey, journey, (tenant, s, cause) -> {
            throw new IllegalStateException("database down");
        }, sessions, new AuthenticSourceConfigs(tenant -> Optional.ofNullable(rows.get(tenant)),
                new AdapterRegistry(List.of(adapter))), TransactionOperations.withoutTransaction(), clock);

        assertThatThrownBy(() -> failing.check(CGCOM, session)).isInstanceOf(IllegalStateException.class);
        failing.close();
    }

    @Test
    @DisplayName("ES-06 — check_writingTheEntitlementFails_isAnErrorAndTheStepIsNotReached")
    void check_writingTheEntitlementFails_isAnErrorAndTheStepIsNotReached() {
        SessionId session = journeyWithDni("00000001R");
        UUID id = journeyId(session);
        EntitlementService failing = new EntitlementService(journey, journey, journey, new InMemoryJourneySessions() {
            @Override
            public Optional<String> read(TenantId tenant, SessionId s, Section section) {
                return sessions.read(tenant, s, section);
            }

            @Override
            public void write(TenantId tenant, SessionId s, Section section, String content) {
                throw new IllegalStateException("database down");
            }
        }, new AuthenticSourceConfigs(tenant -> Optional.ofNullable(rows.get(tenant)),
                new AdapterRegistry(List.of(adapter))), TransactionOperations.withoutTransaction(), clock);

        assertThatThrownBy(() -> failing.check(CGCOM, session)).isInstanceOf(IllegalStateException.class);
        assertThat(stored(id).step()).isEqualTo(JourneyStep.STARTED);
        failing.close();
    }
}
