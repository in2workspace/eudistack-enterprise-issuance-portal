package es.in2.issuanceportal.authenticsource.application;

import es.in2.issuanceportal.MutableClock;
import es.in2.issuanceportal.authenticsource.RecordingAuthenticSourceAuditTrail;
import es.in2.issuanceportal.authenticsource.StubAdapter;
import es.in2.issuanceportal.authenticsource.application.port.AuthenticSourceAuditTrail;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
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
    private final RecordingAuthenticSourceAuditTrail trail = new RecordingAuthenticSourceAuditTrail();
    private final EntitlementService service = new EntitlementService(journey, journey, journey, sessions,
            new AuthenticSourceConfigs(tenant -> Optional.ofNullable(rows.get(tenant)),
                    new AdapterRegistry(List.of(adapter))),
            trail, TransactionOperations.withoutTransaction(), clock, new LookupSlots(100, 100));

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
                new CountDownLatch(1).await(5, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
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
    void check_sourceFails_isUnavailableNeverConfirmed(CapturedOutput output) {
        SessionId session = journeyWithDni("00000001R");
        adapter.answering(searched -> {
            throw new IllegalStateException("source said 00000001R is unknown");
        });

        assertThatThrownBy(() -> service.check(CGCOM, session)).isInstanceOfSatisfying(ProblemException.class,
                e -> assertThat(e.problem()).isEqualTo(ProblemCode.AUTHENTIC_SOURCE_UNAVAILABLE));
        assertThat(stored(journeyId(session)).state()).isEqualTo(JourneyState.IN_PROGRESS);
        assertThat(output.getOut()).contains("Authentic source simulated of tenant cgcom failed: IllegalStateException")
                .doesNotContain("00000001R");
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
    void check_identityWithInvalidShape_isStepNotAllowed(CapturedOutput output) {
        configure(null);
        SessionId session = journey.start(CGCOM, Optional.empty()).session();
        sessions.write(CGCOM, session, Section.IDENTITY,
                "{\"given_name\":\"L\",\"family_name\":\"P\",\"personal_identifier\":{\"type\":\"nif\",\"value\":\"1\"}}");

        assertThatThrownBy(() -> service.check(CGCOM, session)).isInstanceOfSatisfying(ProblemException.class,
                e -> assertThat(e.problem()).isEqualTo(ProblemCode.STEP_NOT_ALLOWED));
        assertThat(adapter.searches()).isEmpty();
        assertThat(output.getOut()).contains("Identity of the journey session is invalid at personal_identifier.type")
                .doesNotContain("\"nif\"");
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
    @DisplayName("AC-11 — check_confirmed_tracesTheCheckWithoutPersonalData")
    void check_confirmed_tracesTheCheckWithoutPersonalData() {
        SessionId session = journeyWithDni("00000001R");
        UUID id = journeyId(session);

        service.check(CGCOM, session);

        assertThat(trail.events()).singleElement().satisfies(event -> {
            assertThat(event.journeyId()).isEqualTo(id);
            assertThat(event.attestationType()).isEqualTo(DOCTORID);
            assertThat(event.adapter()).isEqualTo("simulated");
            assertThat(event.identifierType()).isEqualTo(IdentifierType.DNI);
            assertThat(event.result()).isEqualTo(AuthenticSourceAuditTrail.Result.CONFIRMED);
            assertThat(event.reason()).isEmpty();
            assertThat(event.duration().isNegative()).isFalse();
            assertThat(event.occurredAt()).isEqualTo(clock.instant());
            assertThat(event.toString()).doesNotContain("00000001R", "Lucía", "280000001", "Madrid");
        });
    }

    @Test
    @DisplayName("AC-08, AC-11 — check_identifierMismatch_tracesTheDetailedReason")
    void check_identifierMismatch_tracesTheDetailedReason() {
        adapter.answering(searched -> LookupResult.found(new SourceRecord(
                List.of(new PersonalIdentifier(IdentifierType.DNI, "00000003A")), true, Map.of())));
        SessionId session = journeyWithDni("00000001R");

        service.check(CGCOM, session);

        assertThat(trail.events()).singleElement().satisfies(event -> {
            assertThat(event.result()).isEqualTo(AuthenticSourceAuditTrail.Result.NOT_CONFIRMED);
            assertThat(event.reason()).contains(NotConfirmedReason.IDENTIFIER_MISMATCH);
        });
    }

    @Test
    @DisplayName("AC-11 — check_sourceFails_tracesUnavailable")
    void check_sourceFails_tracesUnavailable() {
        SessionId session = journeyWith(es.in2.issuanceportal.session.api.IdentifierType.COLEGIADO, "280000001", null);
        adapter.answering(searched -> {
            throw new IllegalStateException("down");
        });

        assertThatThrownBy(() -> service.check(CGCOM, session)).isInstanceOf(ProblemException.class);

        assertThat(trail.events()).singleElement().satisfies(event -> {
            assertThat(event.result()).isEqualTo(AuthenticSourceAuditTrail.Result.UNAVAILABLE);
            assertThat(event.identifierType()).isEqualTo(IdentifierType.COLEGIADO);
        });
    }

    @Test
    @DisplayName("AC-10 — check_withoutSource_warnsWithoutPersonalData")
    void check_withoutSource_warnsWithoutPersonalData(CapturedOutput output) {
        SessionId session = journeyWithDni("00000001R");
        rows.put(CGCOM, new AuthenticSourceConfig("employee.sd.1", Set.of(), Map.of()));

        service.check(CGCOM, session);

        assertThat(output.getAll())
                .contains("confirmed for employee.sd.1 without an authentic source")
                .doesNotContain("00000001R");
    }

    @Test
    @DisplayName("AC-10, AC-11 — check_withoutSource_tracesNoAdapter")
    void check_withoutSource_tracesNoAdapter() {
        SessionId session = journeyWithDni("00000001R");
        rows.put(CGCOM, new AuthenticSourceConfig("employee.sd.1", Set.of(), Map.of()));

        service.check(CGCOM, session);

        assertThat(trail.events()).singleElement().satisfies(event -> {
            assertThat(event.adapter()).isEqualTo("none");
            assertThat(event.result()).isEqualTo(AuthenticSourceAuditTrail.Result.CONFIRMED);
        });
    }

    @Test
    @DisplayName("ES-05 — check_trailFails_answersTheSame")
    void check_trailFails_answersTheSame(CapturedOutput output) {
        SessionId session = journeyWithDni("00000001R");
        EntitlementService failingTrail = new EntitlementService(journey, journey, journey, sessions,
                new AuthenticSourceConfigs(tenant -> Optional.ofNullable(rows.get(tenant)),
                        new AdapterRegistry(List.of(adapter))),
                (tenant, event) -> {
                    throw new IllegalStateException("log sink down");
                }, TransactionOperations.withoutTransaction(), clock, new LookupSlots(100, 100));

        assertThat(failingTrail.check(CGCOM, session)).isInstanceOf(EntitlementService.Outcome.Confirmed.class);
        assertThat(output.getOut()).contains("Could not record the entitlement check of journey")
                .doesNotContain("00000001R", "Lucía");
        failingTrail.close();
    }

    @Test
    @DisplayName("ES-04 — check_withdrawalFails_isAnErrorNeverAResult")
    void check_withdrawalFails_isAnErrorNeverAResult() {
        SessionId session = journeyWithDni("00000002W");
        EntitlementService failing = new EntitlementService(journey, journey, (tenant, s, cause) -> {
            throw new IllegalStateException("database down");
        }, sessions, new AuthenticSourceConfigs(tenant -> Optional.ofNullable(rows.get(tenant)),
                new AdapterRegistry(List.of(adapter))), trail, TransactionOperations.withoutTransaction(), clock,
                new LookupSlots(100, 100));

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
            public Optional<UUID> journeyOf(TenantId tenant, SessionId s) {
                return sessions.journeyOf(tenant, s);
            }

            @Override
            public Optional<String> read(TenantId tenant, SessionId s, Section section) {
                return sessions.read(tenant, s, section);
            }

            @Override
            public void write(TenantId tenant, SessionId s, Section section, String content) {
                throw new IllegalStateException("database down");
            }
        }, new AuthenticSourceConfigs(tenant -> Optional.ofNullable(rows.get(tenant)),
                new AdapterRegistry(List.of(adapter))), trail, TransactionOperations.withoutTransaction(), clock,
                new LookupSlots(100, 100));

        assertThatThrownBy(() -> failing.check(CGCOM, session)).isInstanceOf(IllegalStateException.class);
        assertThat(stored(id).step()).isEqualTo(JourneyStep.STARTED);
        failing.close();
    }

    @Test
    @DisplayName("AC-13 — check_identityTypeTheSourceIsNotSearchedBy_isConfigUnavailable")
    void check_identityTypeTheSourceIsNotSearchedBy_isConfigUnavailable() {
        SessionId session = journeyWith(es.in2.issuanceportal.session.api.IdentifierType.COLEGIADO, "280000001", null);
        rows.put(CGCOM, new AuthenticSourceConfig(DOCTORID, Set.of(), Map.of(DOCTORID,
                new SourceSettings("DoctorID", "simulated", null, null, null, Set.of(IdentifierType.DNI), MAPPING,
                        null, null))));

        assertThatThrownBy(() -> service.check(CGCOM, session)).isInstanceOfSatisfying(ProblemException.class,
                e -> assertThat(e.problem()).isEqualTo(ProblemCode.CONFIG_UNAVAILABLE));
        assertThat(adapter.searches()).isEmpty();
        assertThat(stored(journeyId(session)).state()).isEqualTo(JourneyState.IN_PROGRESS);
    }

    @Test
    void check_tooManySearchesInFlight_isUnavailableAtOnce() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        adapter.answering(searched -> {
            inside.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            return source(searched);
        });
        EntitlementService oneAtATime = new EntitlementService(journey, journey, journey, sessions,
                new AuthenticSourceConfigs(tenant -> Optional.ofNullable(rows.get(tenant)),
                        new AdapterRegistry(List.of(adapter))), trail, TransactionOperations.withoutTransaction(),
                clock, new LookupSlots(1, 1));
        SessionId first = journeyWithDni("00000001R");
        SessionId second = journeyWithDni("00000001R");
        AtomicReference<EntitlementService.Outcome> firstOutcome = new AtomicReference<>();
        Thread firstCheck = Thread.ofVirtual().start(() -> firstOutcome.set(oneAtATime.check(CGCOM, first)));
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> oneAtATime.check(CGCOM, second)).isInstanceOfSatisfying(ProblemException.class,
                e -> assertThat(e.problem()).isEqualTo(ProblemCode.AUTHENTIC_SOURCE_UNAVAILABLE));
        assertThat(adapter.searches()).hasSize(1);

        release.countDown();
        firstCheck.join(5_000);
        assertThat(firstOutcome.get()).isInstanceOf(EntitlementService.Outcome.Confirmed.class);
        assertThat(oneAtATime.check(CGCOM, second)).isInstanceOf(EntitlementService.Outcome.Confirmed.class);
        oneAtATime.close();
    }

    @Test
    void check_requestInterruptedBeforeTheSourceAnswers_givesTheSlotBack() throws Exception {
        EntitlementService oneAtATime = new EntitlementService(journey, journey, journey, sessions,
                new AuthenticSourceConfigs(tenant -> Optional.ofNullable(rows.get(tenant)),
                        new AdapterRegistry(List.of(adapter))), trail, TransactionOperations.withoutTransaction(),
                clock, new LookupSlots(1, 1));
        SessionId interrupted = journeyWithDni("00000001R");
        SessionId next = journeyWithDni("00000001R");
        // The source does not answer until the test lets it: a source that answered before the request
        // started to wait would let the wait return its answer, and the interruption would go unnoticed.
        CountDownLatch sourceMayAnswer = new CountDownLatch(1);
        adapter.answering(searched -> {
            try {
                sourceMayAnswer.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            return source(searched);
        });
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread request = Thread.ofVirtual().unstarted(() -> {
            // The wait for the source fails at once, whether or not the search has started.
            Thread.currentThread().interrupt();
            try {
                oneAtATime.check(CGCOM, interrupted);
            } catch (ProblemException e) {
                failure.set(e);
            }
        });
        try {
            request.start();
            request.join(5_000);
            assertThat(failure.get()).isInstanceOfSatisfying(ProblemException.class,
                    e -> assertThat(e.problem()).isEqualTo(ProblemCode.AUTHENTIC_SOURCE_UNAVAILABLE));

            // The slot is back while the interrupted search is still pending.
            adapter.answering(EntitlementServiceTest::source);
            assertThat(oneAtATime.check(CGCOM, next)).isInstanceOf(EntitlementService.Outcome.Confirmed.class);
        } finally {
            sourceMayAnswer.countDown();
            oneAtATime.close();
        }
    }

    @Test
    void check_sourceSlowerThanTheTimeout_givesTheSlotBack() {
        EntitlementService oneAtATime = new EntitlementService(journey, journey, journey, sessions,
                new AuthenticSourceConfigs(tenant -> Optional.ofNullable(rows.get(tenant)),
                        new AdapterRegistry(List.of(adapter))), trail, TransactionOperations.withoutTransaction(),
                clock, new LookupSlots(1, 1));
        SessionId slow = journeyWithDni("00000001R");
        SessionId next = journeyWithDni("00000001R");
        configure(Duration.ofSeconds(1));
        adapter.answering(searched -> {
            try {
                new CountDownLatch(1).await(5, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            return source(searched);
        });
        assertThatThrownBy(() -> oneAtATime.check(CGCOM, slow)).isInstanceOf(ProblemException.class);

        adapter.answering(EntitlementServiceTest::source);
        assertThat(oneAtATime.check(CGCOM, next)).isInstanceOf(EntitlementService.Outcome.Confirmed.class);
        oneAtATime.close();
    }
}
