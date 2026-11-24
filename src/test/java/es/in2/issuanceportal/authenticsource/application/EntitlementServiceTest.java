package es.in2.issuanceportal.authenticsource.application;

import es.in2.issuanceportal.MutableClock;
import es.in2.issuanceportal.authenticsource.RecordingAuthenticSourceAuditTrail;
import es.in2.issuanceportal.authenticsource.StubAdapter;
import es.in2.issuanceportal.authenticsource.application.port.AuthenticSourceAuditTrail;
import es.in2.issuanceportal.authenticsource.application.port.DisputeNotSupportedException;
import es.in2.issuanceportal.authenticsource.domain.AttestationPreview;
import es.in2.issuanceportal.authenticsource.domain.Holder;
import es.in2.issuanceportal.authenticsource.domain.IdentifierType;
import es.in2.issuanceportal.authenticsource.domain.NotConfirmedReason;
import es.in2.issuanceportal.authenticsource.domain.PersonalIdentifier;
import es.in2.issuanceportal.authenticsource.domain.RequestStatus;
import es.in2.issuanceportal.authenticsource.domain.SourceRequest;
import es.in2.issuanceportal.authenticsource.domain.SourceRequests;
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
import es.in2.issuanceportal.session.Holders;
import es.in2.issuanceportal.session.api.HolderRef;
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
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class EntitlementServiceTest {

    private static final TenantId CGCOM = new TenantId("cgcom");
    private static final String DOCTORID = "doctorid.sd.1";
    private static final PersonalIdentifier ACTIVE_DNI = new PersonalIdentifier(IdentifierType.PERSONAL_ID, "00000001R");
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
            trail, TransactionOperations.withoutTransaction(), clock, new LookupSlots(100, 100), (t, j, r) -> false);

    private static SourceRequest request(String ref, RequestStatus status, Map<String, Object> attributes) {
        return new SourceRequest(ref, status, List.of(), false, null, null, null, null, attributes);
    }

    /** Two people: the active one, findable by DNI and by registration number, and one without requests. */
    private static SourceRequests source(Holder searched) {
        PersonalIdentifier identifier = searched.identifier();
        if (identifier.sameAs(ACTIVE_DNI) || identifier.sameAs(ACTIVE_COLEGIADO)) {
            return SourceRequests.of(request("SIM-1", RequestStatus.AVAILABLE, Map.of(
                    "registration_number", "280000001",
                    "medical_boards", List.of("Madrid"),
                    "entitled_to_practise", true)));
        }
        if (identifier.sameAs(new PersonalIdentifier(IdentifierType.PERSONAL_ID, "00000002W"))) {
            return SourceRequests.noRequests();
        }
        return SourceRequests.notFound();
    }

    @AfterEach
    void closeService() {
        service.close();
    }

    private void configure(Duration timeout) {
        rows.put(CGCOM, new AuthenticSourceConfig(DOCTORID, Set.of(IdentifierType.COLEGIADO), Map.of(DOCTORID,
                new SourceSettings("DoctorID", "simulated", null, null, timeout,
                        Set.of(IdentifierType.PERSONAL_ID, IdentifierType.COLEGIADO), MAPPING, "email", null))));
    }

    private HolderRef journeyWith(es.in2.issuanceportal.session.api.IdentifierType type, String value,
                                  String email) {
        configure(null);
        HolderRef holder = startJourney(CGCOM);
        sessions.write(CGCOM, journeyId(holder), Section.IDENTITY, new HolderIdentity("Lucía", "Prueba Sintética",
                new es.in2.issuanceportal.session.api.PersonalIdentifier(type, value), email).toJson());
        return holder;
    }

    private HolderRef journeyWithDni(String dni) {
        return journeyWith(es.in2.issuanceportal.session.api.IdentifierType.PERSONAL_ID, dni, null);
    }

    private Journey stored(UUID id) {
        return journeys.findById(CGCOM, id).orElseThrow();
    }

    private UUID journeyId(HolderRef holder) {
        return sessions.journeyOf(CGCOM, holder).orElseThrow();
    }

    @Test
    @DisplayName("AC-03, AC-04 — check_activeByDni_confirmsWritesTheEntitlementAndReachesTheStep")
    void check_activeByDni_confirmsWritesTheEntitlementAndReachesTheStep() {
        HolderRef holder = journeyWithDni("00000001R");
        UUID id = journeyId(holder);

        EntitlementService.Outcome outcome = service.check(CGCOM, holder);

        assertThat(adapter.searches()).containsExactly(ACTIVE_DNI);
        EntitlementService.CheckedRequest request = ((EntitlementService.Outcome.Confirmed) outcome).requests()
                .getFirst();
        assertThat(request.id()).isEqualTo("r1");
        assertThat(request.status()).isEqualTo(RequestStatus.AVAILABLE);
        AttestationPreview preview = request.preview().orElseThrow();
        assertThat(preview.typeName()).isEqualTo("DoctorID");
        assertThat(preview.fields()).extracting(AttestationPreview.Field::value)
                .contains("Lucía Prueba Sintética", "280000001", "Madrid", "Sí")
                .doesNotContain("00000001R");
        EntitlementRecord written = EntitlementRecord.fromJson(
                sessions.read(CGCOM, journeyId(holder), Section.ENTITLEMENT).orElseThrow());
        assertThat(written.identifierTypeIfSearched()).contains(es.in2.issuanceportal.session.api.IdentifierType.PERSONAL_ID);
        assertThat(written.emailIfKnown()).isEmpty();
        assertThat(written.requests()).singleElement().satisfies(entry -> {
            assertThat(entry.id()).isEqualTo("r1");
            assertThat(entry.sourceRef()).isEqualTo("SIM-1");
            assertThat(entry.claims()).containsOnlyKeys("personal_administrative_number", "medical_board",
                    "legally_entitled");
            assertThat(entry.preview().fields()).hasSize(4);
        });
        assertThat(written.checkedAt()).isEqualTo(clock.instant());
        assertThat(stored(id).step()).isEqualTo(JourneyStep.ENTITLEMENT_CONFIRMED);
    }

    @Test
    @DisplayName("AC-05 — check_doctorIdLogin_searchesByRegistrationNumberOnly")
    void check_doctorIdLogin_searchesByRegistrationNumberOnly() {
        HolderRef holder = journeyWith(es.in2.issuanceportal.session.api.IdentifierType.COLEGIADO, "280000001", null);

        assertThat(service.check(CGCOM, holder)).isInstanceOf(EntitlementService.Outcome.Confirmed.class);
        assertThat(adapter.searches()).containsExactly(ACTIVE_COLEGIADO);
    }

    @ParameterizedTest
    @CsvSource({"personal_id,00000002W,NO_REQUESTS", "personal_id,00000009Y,NOT_FOUND", "colegiado,289999999,NOT_FOUND"})
    @DisplayName("AC-03 — check_withoutRequestsOrUnknown_isNotConfirmedAndWithdrawsTheJourney")
    void check_withoutRequestsOrUnknown_isNotConfirmedAndWithdrawsTheJourney(String type, String value,
                                                                            NotConfirmedReason reason) {
        HolderRef holder = journeyWith(es.in2.issuanceportal.session.api.IdentifierType.fromCode(type).orElseThrow(),
                value, null);
        UUID id = journeyId(holder);

        assertThat(service.check(CGCOM, holder))
                .isEqualTo(new EntitlementService.Outcome.NotConfirmed(Optional.of(reason), List.of()));

        Journey withdrawn = stored(id);
        assertThat(withdrawn.state()).isEqualTo(JourneyState.WITHDRAWN);
        assertThat(withdrawn.withdrawalReason()).contains(WithdrawalReason.NOT_ENTITLED);
        assertThat(sessions.journeyOf(CGCOM, holder)).isEmpty();
        assertThatThrownBy(() -> service.check(CGCOM, holder)).isInstanceOf(JourneyClosedException.class);
    }

    @Test
    @DisplayName("AC-04 — check_noRequestAvailable_isNotConfirmedWithoutReasonAndWithdraws")
    void check_noRequestAvailable_isNotConfirmedWithoutReasonAndWithdraws() {
        adapter.answering(searched -> SourceRequests.of(request("SIM-1", RequestStatus.IDENTITY_MISMATCH, Map.of()),
                request("SIM-2", RequestStatus.IDENTIFIER_MISMATCH, Map.of())));
        HolderRef holder = journeyWithDni("00000001R");
        UUID id = journeyId(holder);

        EntitlementService.Outcome outcome = service.check(CGCOM, holder);

        assertThat(outcome).isInstanceOf(EntitlementService.Outcome.NotConfirmed.class);
        assertThat(((EntitlementService.Outcome.NotConfirmed) outcome).reason()).isEmpty();
        assertThat(outcome.requests()).extracting(EntitlementService.CheckedRequest::id, EntitlementService.CheckedRequest::status)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("r1", RequestStatus.IDENTITY_MISMATCH),
                        org.assertj.core.groups.Tuple.tuple("r2", RequestStatus.IDENTIFIER_MISMATCH));
        assertThat(outcome.requests()).allSatisfy(request -> assertThat(request.preview()).isEmpty());
        assertThat(stored(id).withdrawalReason()).contains(WithdrawalReason.NOT_ENTITLED);
        assertThat(sessions.journeyOf(CGCOM, holder)).isEmpty();
    }

    @Test
    @DisplayName("EC-08 — check_oneAvailableAndOneMismatch_isConfirmedWithTheAvailableOneData")
    void check_oneAvailableAndOneMismatch_isConfirmedWithTheAvailableOneData() {
        adapter.answering(searched -> SourceRequests.of(
                request("SIM-1", RequestStatus.IDENTITY_MISMATCH, Map.of("registration_number", "280000009")),
                request("SIM-2", RequestStatus.AVAILABLE, Map.of("registration_number", "280000001"))));
        HolderRef holder = journeyWithDni("00000001R");

        EntitlementService.Outcome outcome = service.check(CGCOM, holder);

        assertThat(outcome.requests()).extracting(EntitlementService.CheckedRequest::id,
                        EntitlementService.CheckedRequest::status)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("r1", RequestStatus.IDENTITY_MISMATCH),
                        org.assertj.core.groups.Tuple.tuple("r2", RequestStatus.AVAILABLE));
        assertThat(outcome.requests().get(0).preview()).isEmpty();
        assertThat(outcome.requests().get(1).preview().orElseThrow().fields())
                .extracting(AttestationPreview.Field::value).contains("280000001").doesNotContain("280000009");
        EntitlementRecord written = EntitlementRecord.fromJson(
                sessions.read(CGCOM, journeyId(holder), Section.ENTITLEMENT).orElseThrow());
        assertThat(written.requests()).extracting(EntitlementRecord.Request::claims)
                .satisfiesExactly(first -> assertThat(first).isEmpty(),
                        second -> assertThat(second).containsKey("personal_administrative_number"));
    }

    @Test
    @DisplayName("AC-16 — check_certificateLogin_theSourceGetsTheNameTheFamilyNamesAndThePersonalId")
    void check_certificateLogin_theSourceGetsTheNameTheFamilyNamesAndThePersonalId() {
        HolderRef holder = journeyWithDni("00000001R");

        service.check(CGCOM, holder);

        assertThat(adapter.holders()).singleElement().satisfies(searched -> {
            assertThat(searched.givenName()).isEqualTo("Lucía");
            assertThat(searched.familyNames()).isEqualTo("Prueba Sintética");
            assertThat(searched.identifier()).isEqualTo(ACTIVE_DNI);
        });
    }

    @Test
    @DisplayName("AC-09 — check_sourceSlowerThanTheTimeout_isUnavailableAndTheJourneyGoesOn")
    void check_sourceSlowerThanTheTimeout_isUnavailableAndTheJourneyGoesOn() {
        HolderRef holder = journeyWithDni("00000001R");
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
        assertThatThrownBy(() -> service.check(CGCOM, holder)).isInstanceOfSatisfying(ProblemException.class, e -> {
            assertThat(e.problem()).isEqualTo(ProblemCode.AUTHENTIC_SOURCE_UNAVAILABLE);
            assertThat(e.retryAfter()).contains(Duration.ofSeconds(30));
        });
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));

        assertThat(sessions.read(CGCOM, journeyId(holder), Section.ENTITLEMENT)).isEmpty();
        assertThat(stored(journeyId(holder)).state()).isEqualTo(JourneyState.IN_PROGRESS);
        adapter.answering(EntitlementServiceTest::source);
        assertThat(service.check(CGCOM, holder)).isInstanceOf(EntitlementService.Outcome.Confirmed.class);
    }

    @Test
    @DisplayName("ES-01 — check_sourceFails_isUnavailableNeverConfirmed")
    void check_sourceFails_isUnavailableNeverConfirmed(CapturedOutput output) {
        HolderRef holder = journeyWithDni("00000001R");
        adapter.answering(searched -> {
            throw new IllegalStateException("source said 00000001R is unknown");
        });

        assertThatThrownBy(() -> service.check(CGCOM, holder)).isInstanceOfSatisfying(ProblemException.class,
                e -> assertThat(e.problem()).isEqualTo(ProblemCode.AUTHENTIC_SOURCE_UNAVAILABLE));
        assertThat(stored(journeyId(holder)).state()).isEqualTo(JourneyState.IN_PROGRESS);
        assertThat(output.getOut()).contains("Authentic source simulated of tenant cgcom failed: IllegalStateException")
                .doesNotContain("00000001R");
    }

    @Test
    @DisplayName("ES-01 — check_sourceAnswersNothing_isUnavailable")
    void check_sourceAnswersNothing_isUnavailable() {
        HolderRef holder = journeyWithDni("00000001R");
        adapter.answering(searched -> null);

        assertThatThrownBy(() -> service.check(CGCOM, holder)).isInstanceOfSatisfying(ProblemException.class,
                e -> assertThat(e.problem()).isEqualTo(ProblemCode.AUTHENTIC_SOURCE_UNAVAILABLE));
    }

    @Test
    @DisplayName("AC-10 — check_attestationTypeWithoutSource_confirmsWithoutData")
    void check_attestationTypeWithoutSource_confirmsWithoutData() {
        HolderRef holder = journeyWith(es.in2.issuanceportal.session.api.IdentifierType.PERSONAL_ID, "00000001R",
                "lucia@example.org");
        rows.put(CGCOM, new AuthenticSourceConfig("employee.sd.1", Set.of(), Map.of()));

        EntitlementService.Outcome outcome = service.check(CGCOM, holder);

        assertThat(outcome.requests()).singleElement().satisfies(request -> {
            assertThat(request.id()).isEqualTo("r1");
            assertThat(request.status()).isEqualTo(RequestStatus.AVAILABLE);
            assertThat(request.preview().orElseThrow().fields()).hasSize(1);
            assertThat(request.mismatchedFields()).isEmpty();
            assertThat(request.renewal()).isFalse();
        });
        assertThat(adapter.searches()).isEmpty();
        EntitlementRecord written = EntitlementRecord.fromJson(
                sessions.read(CGCOM, journeyId(holder), Section.ENTITLEMENT).orElseThrow());
        assertThat(written.requests()).singleElement()
                .satisfies(entry -> assertThat(entry.claims()).isEmpty());
        assertThat(written.emailIfKnown()).contains("lucia@example.org");
        assertThat(written.identifierTypeIfSearched()).isEmpty();
    }

    @Test
    @DisplayName("AC-01 — check_journeyWithoutIdentity_isStepNotAllowed")
    void check_journeyWithoutIdentity_isStepNotAllowed() {
        configure(null);
        HolderRef holder = startJourney(CGCOM);
        sessions.clear(CGCOM, journeyId(holder), Section.IDENTITY);

        assertThatThrownBy(() -> service.check(CGCOM, holder)).isInstanceOfSatisfying(ProblemException.class,
                e -> assertThat(e.problem()).isEqualTo(ProblemCode.STEP_NOT_ALLOWED));
        assertThat(adapter.searches()).isEmpty();
        assertThat(stored(journeyId(holder)).step()).isEqualTo(JourneyStep.IDENTIFIED);
    }

    @Test
    @DisplayName("ES-02 — check_identityWithInvalidShape_isStepNotAllowed")
    void check_identityWithInvalidShape_isStepNotAllowed(CapturedOutput output) {
        configure(null);
        HolderRef holder = startJourney(CGCOM);
        sessions.write(CGCOM, journeyId(holder), Section.IDENTITY,
                "{\"given_name\":\"L\",\"family_name\":\"P\",\"personal_identifier\":{\"type\":\"nif\",\"value\":\"1\"}}");

        assertThatThrownBy(() -> service.check(CGCOM, holder)).isInstanceOfSatisfying(ProblemException.class,
                e -> assertThat(e.problem()).isEqualTo(ProblemCode.STEP_NOT_ALLOWED));
        assertThat(adapter.searches()).isEmpty();
        assertThat(output.getOut()).contains("Identity of the journey session is invalid at personal_identifier.type")
                .doesNotContain("\"nif\"");
    }

    @Test
    @DisplayName("EC-02 — check_again_replacesTheEntitlementAndKeepsTheIds")
    void check_again_replacesTheEntitlementAndKeepsTheIds() {
        HolderRef holder = journeyWithDni("00000001R");
        service.check(CGCOM, holder);
        clock.advance(Duration.ofMinutes(1));

        service.check(CGCOM, holder);

        EntitlementRecord written = EntitlementRecord.fromJson(
                sessions.read(CGCOM, journeyId(holder), Section.ENTITLEMENT).orElseThrow());
        assertThat(written.checkedAt()).isEqualTo(clock.instant());
        assertThat(written.requests()).extracting(EntitlementRecord.Request::id).containsExactly("r1");
        assertThat(stored(journeyId(holder)).step()).isEqualTo(JourneyStep.ENTITLEMENT_CONFIRMED);
    }

    @Test
    @DisplayName("EC-02 — check_again_requestsOfTheSameSourceReferenceKeepTheirIdWhateverTheOrder")
    void check_again_requestsOfTheSameSourceReferenceKeepTheirIdWhateverTheOrder() {
        HolderRef holder = journeyWithDni("00000001R");
        adapter.answering(searched -> SourceRequests.of(request("SIM-A", RequestStatus.AVAILABLE, Map.of()),
                request("SIM-B", RequestStatus.AVAILABLE, Map.of())));
        service.check(CGCOM, holder);
        adapter.answering(searched -> SourceRequests.of(request("SIM-C", RequestStatus.AVAILABLE, Map.of()),
                request("SIM-B", RequestStatus.AVAILABLE, Map.of()), request("SIM-A", RequestStatus.AVAILABLE, Map.of())));

        service.check(CGCOM, holder);

        EntitlementRecord written = EntitlementRecord.fromJson(
                sessions.read(CGCOM, journeyId(holder), Section.ENTITLEMENT).orElseThrow());
        assertThat(written.requests()).extracting(EntitlementRecord.Request::sourceRef, EntitlementRecord.Request::id)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("SIM-C", "r3"),
                        org.assertj.core.groups.Tuple.tuple("SIM-B", "r2"),
                        org.assertj.core.groups.Tuple.tuple("SIM-A", "r1"));
    }

    @Test
    @DisplayName("EC-02 — check_again_aRequestUnderReviewStaysUnderReviewEvenIfTheSourceGivesItAvailable")
    void check_again_aRequestUnderReviewStaysUnderReviewEvenIfTheSourceGivesItAvailable() {
        HolderRef holder = journeyWithDni("00000001R");
        adapter.answering(searched -> SourceRequests.of(request("SIM-A", RequestStatus.AVAILABLE, Map.of()),
                request("SIM-B", RequestStatus.AVAILABLE, Map.of())));
        service.check(CGCOM, holder);
        EntitlementRecord first = EntitlementRecord.fromJson(
                sessions.read(CGCOM, journeyId(holder), Section.ENTITLEMENT).orElseThrow());
        sessions.write(CGCOM, journeyId(holder), Section.ENTITLEMENT, new EntitlementRecord(first.identifierType(),
                first.email(), first.requests().stream().map(entry -> entry.sourceRef().equals("SIM-A")
                        ? new EntitlementRecord.Request(entry.id(), entry.sourceRef(),
                        EntitlementRecord.Status.UNDER_REVIEW, entry.mismatchedFields(), entry.renewal(),
                        entry.requestedBy(), entry.requestedAt(), entry.displayIdentifier(), entry.organization(),
                        entry.claims(), entry.preview())
                        : entry).toList(), first.checkedAt()).toJson());

        EntitlementService.Outcome outcome = service.check(CGCOM, holder);

        assertThat(outcome).isInstanceOf(EntitlementService.Outcome.Confirmed.class);
        assertThat(outcome.requests()).extracting(EntitlementService.CheckedRequest::id,
                        EntitlementService.CheckedRequest::status)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("r1", RequestStatus.UNDER_REVIEW),
                        org.assertj.core.groups.Tuple.tuple("r2", RequestStatus.AVAILABLE));
        assertThat(outcome.requests().getFirst().preview()).isPresent();
        EntitlementRecord written = EntitlementRecord.fromJson(
                sessions.read(CGCOM, journeyId(holder), Section.ENTITLEMENT).orElseThrow());
        assertThat(written.requests().getFirst().status()).isEqualTo(EntitlementRecord.Status.UNDER_REVIEW);
    }

    @Test
    @DisplayName("EC-02 — check_again_onlyRequestUnderReview_isNotConfirmedAndWithdraws")
    void check_again_onlyRequestUnderReview_isNotConfirmedAndWithdraws() {
        HolderRef holder = journeyWithDni("00000001R");
        adapter.answering(searched -> SourceRequests.of(request("SIM-A", RequestStatus.AVAILABLE, Map.of())));
        service.check(CGCOM, holder);
        EntitlementRecord first = EntitlementRecord.fromJson(
                sessions.read(CGCOM, journeyId(holder), Section.ENTITLEMENT).orElseThrow());
        EntitlementRecord.Request entry = first.requests().getFirst();
        sessions.write(CGCOM, journeyId(holder), Section.ENTITLEMENT, new EntitlementRecord(first.identifierType(),
                first.email(), List.of(new EntitlementRecord.Request(entry.id(), entry.sourceRef(),
                EntitlementRecord.Status.UNDER_REVIEW, entry.mismatchedFields(), entry.renewal(),
                entry.requestedBy(), entry.requestedAt(), entry.displayIdentifier(), entry.organization(),
                entry.claims(), entry.preview())), first.checkedAt()).toJson());
        UUID id = journeyId(holder);

        EntitlementService.Outcome outcome = service.check(CGCOM, holder);

        assertThat(outcome).isInstanceOf(EntitlementService.Outcome.NotConfirmed.class);
        assertThat(outcome.requests()).extracting(EntitlementService.CheckedRequest::status)
                .containsExactly(RequestStatus.UNDER_REVIEW);
        assertThat(stored(id).withdrawalReason()).contains(WithdrawalReason.NOT_ENTITLED);
    }

    @Test
    @DisplayName("EC-02 — check_earlierSectionInThePreviousShape_isReplaced")
    void check_earlierSectionInThePreviousShape_isReplaced() {
        HolderRef holder = journeyWithDni("00000001R");
        sessions.write(CGCOM, journeyId(holder), Section.ENTITLEMENT, "{\"result\":\"confirmed\","
                + "\"claims\":{},\"preview\":{\"type_name\":\"DoctorID\",\"fields\":[]},"
                + "\"checked_at\":\"2026-10-28T10:00:00Z\"}");

        assertThat(service.check(CGCOM, holder)).isInstanceOf(EntitlementService.Outcome.Confirmed.class);

        assertThat(EntitlementRecord.fromJson(sessions.read(CGCOM, journeyId(holder), Section.ENTITLEMENT)
                .orElseThrow()).requests()).extracting(EntitlementRecord.Request::id).containsExactly("r1");
    }

    @Test
    @DisplayName("EC-04 — check_journeyInactiveForFifteenMinutes_isJourneyClosed")
    void check_journeyInactiveForFifteenMinutes_isJourneyClosed() {
        HolderRef holder = journeyWithDni("00000001R");
        clock.advance(Duration.ofMinutes(16));

        assertThatThrownBy(() -> service.check(CGCOM, holder)).isInstanceOf(JourneyClosedException.class);
        assertThat(adapter.searches()).isEmpty();
    }

    @Test
    @DisplayName("AC-11 — check_confirmed_tracesTheCheckWithoutPersonalData")
    void check_confirmed_tracesTheCheckWithoutPersonalData() {
        HolderRef holder = journeyWithDni("00000001R");
        UUID id = journeyId(holder);

        service.check(CGCOM, holder);

        assertThat(trail.events()).singleElement().satisfies(event -> {
            assertThat(event.journeyId()).isEqualTo(id);
            assertThat(event.attestationType()).isEqualTo(DOCTORID);
            assertThat(event.adapter()).isEqualTo("simulated");
            assertThat(event.identifierType()).isEqualTo(IdentifierType.PERSONAL_ID);
            assertThat(event.result()).isEqualTo(AuthenticSourceAuditTrail.Result.CONFIRMED);
            assertThat(event.reason()).isEmpty();
            assertThat(event.duration().isNegative()).isFalse();
            assertThat(event.occurredAt()).isEqualTo(clock.instant());
            assertThat(event.toString()).doesNotContain("00000001R", "Lucía", "280000001", "Madrid");
        });
    }

    @Test
    @DisplayName("AC-03, AC-11 — check_holderWithoutRequests_tracesTheReason")
    void check_holderWithoutRequests_tracesTheReason() {
        HolderRef holder = journeyWithDni("00000002W");

        service.check(CGCOM, holder);

        assertThat(trail.events()).singleElement().satisfies(event -> {
            assertThat(event.result()).isEqualTo(AuthenticSourceAuditTrail.Result.NOT_CONFIRMED);
            assertThat(event.reason()).contains(NotConfirmedReason.NO_REQUESTS);
        });
    }

    @Test
    @DisplayName("AC-11 — check_sourceFails_tracesUnavailable")
    void check_sourceFails_tracesUnavailable() {
        HolderRef holder = journeyWith(es.in2.issuanceportal.session.api.IdentifierType.COLEGIADO, "280000001", null);
        adapter.answering(searched -> {
            throw new IllegalStateException("down");
        });

        assertThatThrownBy(() -> service.check(CGCOM, holder)).isInstanceOf(ProblemException.class);

        assertThat(trail.events()).singleElement().satisfies(event -> {
            assertThat(event.result()).isEqualTo(AuthenticSourceAuditTrail.Result.UNAVAILABLE);
            assertThat(event.identifierType()).isEqualTo(IdentifierType.COLEGIADO);
        });
    }

    @Test
    @DisplayName("AC-10 — check_withoutSource_warnsWithoutPersonalData")
    void check_withoutSource_warnsWithoutPersonalData(CapturedOutput output) {
        HolderRef holder = journeyWithDni("00000001R");
        rows.put(CGCOM, new AuthenticSourceConfig("employee.sd.1", Set.of(), Map.of()));

        service.check(CGCOM, holder);

        assertThat(output.getAll())
                .contains("confirmed for employee.sd.1 without an authentic source")
                .doesNotContain("00000001R");
    }

    @Test
    @DisplayName("AC-10, AC-11 — check_withoutSource_tracesNoAdapter")
    void check_withoutSource_tracesNoAdapter() {
        HolderRef holder = journeyWithDni("00000001R");
        rows.put(CGCOM, new AuthenticSourceConfig("employee.sd.1", Set.of(), Map.of()));

        service.check(CGCOM, holder);

        assertThat(trail.events()).singleElement().satisfies(event -> {
            assertThat(event.adapter()).isEqualTo("none");
            assertThat(event.result()).isEqualTo(AuthenticSourceAuditTrail.Result.CONFIRMED);
        });
    }

    @Test
    @DisplayName("ES-05 — check_trailFails_answersTheSame")
    void check_trailFails_answersTheSame(CapturedOutput output) {
        HolderRef holder = journeyWithDni("00000001R");
        EntitlementService failingTrail = new EntitlementService(journey, journey, journey, sessions,
                new AuthenticSourceConfigs(tenant -> Optional.ofNullable(rows.get(tenant)),
                        new AdapterRegistry(List.of(adapter))),
                new AuthenticSourceAuditTrail() {
                    @Override
                    public void append(TenantId tenant, Event event) {
                        throw new IllegalStateException("log sink down");
                    }

                    @Override
                    public void appendDispute(TenantId tenant, DisputeEvent event) {
                        throw new IllegalStateException("log sink down");
                    }
                }, TransactionOperations.withoutTransaction(), clock, new LookupSlots(100, 100), (t, j, r) -> false);

        assertThat(failingTrail.check(CGCOM, holder)).isInstanceOf(EntitlementService.Outcome.Confirmed.class);
        assertThat(output.getOut()).contains("Could not record the entitlement check of journey")
                .doesNotContain("00000001R", "Lucía");
        failingTrail.close();
    }

    @Test
    @DisplayName("ES-04 — check_withdrawalFails_isAnErrorNeverAResult")
    void check_withdrawalFails_isAnErrorNeverAResult() {
        HolderRef holder = journeyWithDni("00000002W");
        EntitlementService failing = new EntitlementService(journey, journey, (tenant, s, cause) -> {
            throw new IllegalStateException("database down");
        }, sessions, new AuthenticSourceConfigs(tenant -> Optional.ofNullable(rows.get(tenant)),
                new AdapterRegistry(List.of(adapter))), trail, TransactionOperations.withoutTransaction(), clock,
                new LookupSlots(100, 100), (t, j, r) -> false);

        assertThatThrownBy(() -> failing.check(CGCOM, holder)).isInstanceOf(IllegalStateException.class);
        failing.close();
    }

    @Test
    @DisplayName("ES-06 — check_writingTheEntitlementFails_isAnErrorAndTheStepIsNotReached")
    void check_writingTheEntitlementFails_isAnErrorAndTheStepIsNotReached() {
        HolderRef holder = journeyWithDni("00000001R");
        UUID id = journeyId(holder);
        EntitlementService failing = new EntitlementService(journey, journey, journey, new InMemoryJourneySessions() {
            @Override
            public Optional<UUID> journeyOf(TenantId tenant, HolderRef s) {
                return sessions.journeyOf(tenant, s);
            }

            @Override
            public Optional<String> read(TenantId tenant, UUID journeyId, Section section) {
                return sessions.read(tenant, journeyId, section);
            }

            @Override
            public void write(TenantId tenant, UUID journeyId, Section section, String content) {
                throw new IllegalStateException("database down");
            }
        }, new AuthenticSourceConfigs(tenant -> Optional.ofNullable(rows.get(tenant)),
                new AdapterRegistry(List.of(adapter))), trail, TransactionOperations.withoutTransaction(), clock,
                new LookupSlots(100, 100), (t, j, r) -> false);

        assertThatThrownBy(() -> failing.check(CGCOM, holder)).isInstanceOf(IllegalStateException.class);
        assertThat(stored(id).step()).isEqualTo(JourneyStep.IDENTIFIED);
        failing.close();
    }

    @Test
    @DisplayName("AC-20 — check_identityTypeTheSourceDoesNotAccept_isNotFoundWithoutAskingAndWithdraws")
    void check_identityTypeTheSourceDoesNotAccept_isNotFoundWithoutAskingAndWithdraws(CapturedOutput output) {
        HolderRef holder = journeyWith(es.in2.issuanceportal.session.api.IdentifierType.COLEGIADO, "280000001", null);
        rows.put(CGCOM, new AuthenticSourceConfig(DOCTORID, Set.of(), Map.of(DOCTORID,
                new SourceSettings("DoctorID", "simulated", null, null, null, Set.of(IdentifierType.PERSONAL_ID), MAPPING,
                        null, null))));

        UUID id = journeyId(holder);

        assertThat(service.check(CGCOM, holder)).isEqualTo(
                new EntitlementService.Outcome.NotConfirmed(Optional.of(NotConfirmedReason.NOT_FOUND), List.of()));

        assertThat(adapter.searches()).isEmpty();
        assertThat(stored(id).state()).isEqualTo(JourneyState.WITHDRAWN);
        assertThat(stored(id).withdrawalReason()).contains(WithdrawalReason.NOT_ENTITLED);
        assertThat(trail.events()).singleElement().satisfies(event -> {
            assertThat(event.result()).isEqualTo(AuthenticSourceAuditTrail.Result.NOT_CONFIRMED);
            assertThat(event.reason()).contains(NotConfirmedReason.NOT_FOUND);
            assertThat(event.identifierType()).isEqualTo(IdentifierType.COLEGIADO);
            assertThat(event.requestsByStatus()).isEmpty();
            assertThat(event.toString()).doesNotContain("280000001", "Lucía");
        });
        assertThat(output.getAll()).doesNotContain("280000001");
    }

    @Test
    @DisplayName("AC-16 — check_passportNumberFromACertificateLogin_reachesTheSourceAsPersonalId")
    void check_passportNumberFromACertificateLogin_reachesTheSourceAsPersonalId() {
        HolderRef holder = journeyWithDni("AB1234567");

        service.check(CGCOM, holder);

        assertThat(adapter.holders()).singleElement().satisfies(searched ->
                assertThat(searched.identifier()).isEqualTo(
                        new PersonalIdentifier(IdentifierType.PERSONAL_ID, "AB1234567")));
    }

    @Test
    @DisplayName("AC-01, AC-11 — check_severalRequests_tracesTheCountPerStatusWithoutData")
    void check_severalRequests_tracesTheCountPerStatusWithoutData(CapturedOutput output) {
        adapter.answering(searched -> SourceRequests.of(
                request("SIM-A", RequestStatus.AVAILABLE, Map.of("registration_number", "280000001")),
                request("SIM-B", RequestStatus.IDENTITY_MISMATCH, Map.of()),
                request("SIM-C", RequestStatus.AVAILABLE, Map.of("registration_number", "280000001"))));
        HolderRef holder = journeyWithDni("00000001R");

        service.check(CGCOM, holder);

        assertThat(trail.events()).singleElement().satisfies(event -> {
            assertThat(event.result()).isEqualTo(AuthenticSourceAuditTrail.Result.CONFIRMED);
            assertThat(event.requestsByStatus()).containsOnly(Map.entry(RequestStatus.AVAILABLE, 2),
                    Map.entry(RequestStatus.IDENTITY_MISMATCH, 1));
            assertThat(event.toString()).doesNotContain("SIM-A", "SIM-B", "280000001", "Lucía");
        });
        assertThat(output.getAll()).doesNotContain("SIM-A", "SIM-B", "SIM-C");
    }

    @Test
    @DisplayName("AC-01 — check_severalRequests_confirmsWithAnIdEachInTheOrderOfTheSource")
    void check_severalRequests_confirmsWithAnIdEachInTheOrderOfTheSource() {
        adapter.answering(searched -> SourceRequests.of(
                request("SIM-A", RequestStatus.AVAILABLE, Map.of("registration_number", "280000001")),
                request("SIM-B", RequestStatus.IDENTITY_MISMATCH, Map.of()),
                request("SIM-C", RequestStatus.AVAILABLE, Map.of("registration_number", "280000003"))));
        HolderRef holder = journeyWithDni("00000001R");

        EntitlementService.Outcome outcome = service.check(CGCOM, holder);

        assertThat(outcome).isInstanceOf(EntitlementService.Outcome.Confirmed.class);
        assertThat(outcome.requests()).extracting(EntitlementService.CheckedRequest::id).containsExactly("r1", "r2", "r3");
        assertThat(outcome.toString()).doesNotContain("SIM-").doesNotContain("280000001");
    }

    @Test
    @DisplayName("ES-04 — check_twoRequestsWithTheSameReference_isUnavailable")
    void check_twoRequestsWithTheSameReference_isUnavailable() {
        adapter.answering(searched -> SourceRequests.of(request("SIM-A", RequestStatus.AVAILABLE, Map.of()),
                request("SIM-A", RequestStatus.AVAILABLE, Map.of())));
        HolderRef holder = journeyWithDni("00000001R");

        assertThatThrownBy(() -> service.check(CGCOM, holder)).isInstanceOfSatisfying(ProblemException.class,
                e -> assertThat(e.problem()).isEqualTo(ProblemCode.AUTHENTIC_SOURCE_UNAVAILABLE));
        assertThat(stored(journeyId(holder)).state()).isEqualTo(JourneyState.IN_PROGRESS);
    }

    @Test
    @DisplayName("EC-04 — check_valuesInTheFormOfAList_arePlacedAsTheSourceGivesThemAndShownJoined")
    void check_valuesInTheFormOfAList_arePlacedAsTheSourceGivesThemAndShownJoined() {
        adapter.answering(searched -> SourceRequests.of(request("SIM-A", RequestStatus.AVAILABLE, Map.of(
                "medical_boards", List.of("Madrid", "Toledo")))));
        HolderRef holder = journeyWithDni("00000001R");

        EntitlementService.Outcome outcome = service.check(CGCOM, holder);

        assertThat(outcome.requests().getFirst().preview().orElseThrow().fields())
                .extracting(AttestationPreview.Field::value).contains("Madrid, Toledo");
        assertThat(EntitlementRecord.fromJson(sessions.read(CGCOM, journeyId(holder), Section.ENTITLEMENT)
                .orElseThrow()).requests().getFirst().claims()).containsEntry("medical_board", List.of("Madrid", "Toledo"));
    }

    @Test
    @DisplayName("AC-14 — check_emailOfTheSource_prefillsTheEmailStepWhenTheTokenHasNone")
    void check_emailOfTheSource_prefillsTheEmailStepWhenTheTokenHasNone() {
        adapter.answering(searched -> SourceRequests.of(request("SIM-A", RequestStatus.AVAILABLE, Map.of(
                "email", "fuente@example.org"))));
        HolderRef holder = journeyWithDni("00000001R");

        service.check(CGCOM, holder);

        assertThat(EntitlementRecord.fromJson(sessions.read(CGCOM, journeyId(holder), Section.ENTITLEMENT)
                .orElseThrow()).emailIfKnown()).contains("fuente@example.org");
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
                clock, new LookupSlots(1, 1), (t, j, r) -> false);
        HolderRef first = journeyWithDni("00000001R");
        HolderRef second = journeyWithDni("00000001R");
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
                clock, new LookupSlots(1, 1), (t, j, r) -> false);
        HolderRef interrupted = journeyWithDni("00000001R");
        HolderRef next = journeyWithDni("00000001R");
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

            // The slot comes back whether the search never started or was interrupted while it ran; in the
            // second case it does so as the search ends, on its own thread. The next request is served once
            // that has happened, never refused for good.
            adapter.answering(EntitlementServiceTest::source);
            assertThat(checkOnceTheSlotIsBack(oneAtATime, next)).isInstanceOf(EntitlementService.Outcome.Confirmed.class);
        } finally {
            sourceMayAnswer.countDown();
            oneAtATime.close();
        }
    }

    /**
     * Checks again while the only slot is still held by a search that is ending on another thread; gives up
     * after five seconds, so a slot that never comes back still fails the test.
     */
    private static EntitlementService.Outcome checkOnceTheSlotIsBack(EntitlementService service, HolderRef holder) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            try {
                return service.check(CGCOM, holder);
            } catch (ProblemException e) {
                if (e.problem() != ProblemCode.AUTHENTIC_SOURCE_UNAVAILABLE || System.nanoTime() > deadline) {
                    throw e;
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            }
        }
    }

    @Test
    void check_sourceSlowerThanTheTimeout_givesTheSlotBack() {
        EntitlementService oneAtATime = new EntitlementService(journey, journey, journey, sessions,
                new AuthenticSourceConfigs(tenant -> Optional.ofNullable(rows.get(tenant)),
                        new AdapterRegistry(List.of(adapter))), trail, TransactionOperations.withoutTransaction(),
                clock, new LookupSlots(1, 1), (t, j, r) -> false);
        HolderRef slow = journeyWithDni("00000001R");
        HolderRef next = journeyWithDni("00000001R");
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

    private HolderRef checkedJourney() {
        HolderRef holder = journeyWithDni("00000001R");
        service.check(CGCOM, holder);
        return holder;
    }

    private EntitlementRecord section(HolderRef holder) {
        return EntitlementRecord.fromJson(sessions.read(CGCOM, journeyId(holder), Section.ENTITLEMENT).orElseThrow());
    }

    private void assertProblem(Runnable call, ProblemCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ProblemException.class,
                e -> assertThat(e.problem()).isEqualTo(code));
    }

    @Test
    @DisplayName("AC-06 — dispute_availableRequest_tellsTheSourceAndBecomesUnderReview")
    void dispute_availableRequest_tellsTheSourceAndBecomesUnderReview() {
        HolderRef holder = checkedJourney();

        service.dispute(CGCOM, holder, "r1");

        assertThat(adapter.disputes()).singleElement().satisfies(dispute -> {
            assertThat(dispute.sourceRef()).isEqualTo("SIM-1");
            assertThat(dispute.holder().givenName()).isEqualTo("Lucía");
            assertThat(dispute.holder().familyNames()).isEqualTo("Prueba Sintética");
            assertThat(dispute.holder().identifier()).isEqualTo(ACTIVE_DNI);
        });
        EntitlementRecord.Request request = section(holder).requests().getFirst();
        assertThat(request.status()).isEqualTo(EntitlementRecord.Status.UNDER_REVIEW);
        assertThat(request.claims()).isNotEmpty();
        assertThat(request.preview()).isNotNull();
        assertThat(trail.disputes()).singleElement().satisfies(event -> {
            assertThat(event.result()).isEqualTo(AuthenticSourceAuditTrail.DisputeResult.SENT);
            assertThat(event.adapter()).isEqualTo("simulated");
            assertThat(event.attestationType()).isEqualTo(DOCTORID);
            assertThat(event.occurredAt()).isEqualTo(clock.instant());
            assertThat(event.toString()).doesNotContain("SIM-1", "00000001R", "Lucía");
        });
    }

    @Test
    @DisplayName("EC-01 — dispute_requestAlreadyUnderReview_doesNothingMore")
    void dispute_requestAlreadyUnderReview_doesNothingMore() {
        HolderRef holder = checkedJourney();
        service.dispute(CGCOM, holder, "r1");

        service.dispute(CGCOM, holder, "r1");

        assertThat(adapter.disputes()).hasSize(1);
        assertThat(trail.disputes()).hasSize(1);
    }

    @Test
    @DisplayName("ES-02 — dispute_unknownId_isRequestNotFound")
    void dispute_unknownId_isRequestNotFound() {
        HolderRef holder = checkedJourney();

        assertProblem(() -> service.dispute(CGCOM, holder, "r9"), ProblemCode.REQUEST_NOT_FOUND);
        assertThat(adapter.disputes()).isEmpty();
        assertThat(trail.disputes()).isEmpty();
    }

    @Test
    @DisplayName("ES-02 — dispute_requestThatIsNotAvailable_isRequestNotAvailable")
    void dispute_requestThatIsNotAvailable_isRequestNotAvailable() {
        adapter.answering(searched -> SourceRequests.of(request("SIM-A", RequestStatus.IDENTITY_MISMATCH, Map.of()),
                request("SIM-B", RequestStatus.IDENTIFIER_MISMATCH, Map.of()),
                request("SIM-C", RequestStatus.AVAILABLE, Map.of())));
        HolderRef holder = journeyWithDni("00000001R");
        service.check(CGCOM, holder);

        assertProblem(() -> service.dispute(CGCOM, holder, "r1"), ProblemCode.REQUEST_NOT_AVAILABLE);
        assertProblem(() -> service.dispute(CGCOM, holder, "r2"), ProblemCode.REQUEST_NOT_AVAILABLE);
        assertThat(adapter.disputes()).isEmpty();
        assertThat(section(holder).requests()).extracting(EntitlementRecord.Request::status)
                .containsExactly(EntitlementRecord.Status.IDENTITY_MISMATCH,
                        EntitlementRecord.Status.IDENTIFIER_MISMATCH, EntitlementRecord.Status.AVAILABLE);
    }

    @Test
    @DisplayName("AC-06 — dispute_requestsNotCheckedYet_isStepNotAllowed")
    void dispute_requestsNotCheckedYet_isStepNotAllowed() {
        HolderRef holder = journeyWithDni("00000001R");

        assertProblem(() -> service.dispute(CGCOM, holder, "r1"), ProblemCode.STEP_NOT_ALLOWED);
        assertThat(adapter.disputes()).isEmpty();
    }

    @Test
    @DisplayName("EC-09 — dispute_sourceThatDoesNotSupportIt_isTracedNotSupportedAndRecorded")
    void dispute_sourceThatDoesNotSupportIt_isTracedNotSupportedAndRecorded() {
        adapter.supportingDispute(false);
        HolderRef holder = checkedJourney();

        service.dispute(CGCOM, holder, "r1");

        assertThat(adapter.disputes()).isEmpty();
        assertThat(section(holder).requests().getFirst().status()).isEqualTo(EntitlementRecord.Status.UNDER_REVIEW);
        assertThat(trail.disputes()).singleElement().satisfies(event ->
                assertThat(event.result()).isEqualTo(AuthenticSourceAuditTrail.DisputeResult.NOT_SUPPORTED));
    }

    @Test
    @DisplayName("EC-09 — dispute_sourceOfThisHolderRefusesIt_isTracedNotSupportedAndRecorded")
    void dispute_sourceOfThisHolderRefusesIt_isTracedNotSupportedAndRecorded() {
        adapter.failingTheDispute(new DisputeNotSupportedException());
        HolderRef holder = checkedJourney();

        service.dispute(CGCOM, holder, "r1");

        assertThat(section(holder).requests().getFirst().status()).isEqualTo(EntitlementRecord.Status.UNDER_REVIEW);
        assertThat(trail.disputes()).singleElement().satisfies(event ->
                assertThat(event.result()).isEqualTo(AuthenticSourceAuditTrail.DisputeResult.NOT_SUPPORTED));
    }

    @Test
    @DisplayName("AC-06, NFR-4 — dispute_sourceFails_isTracedFailedRecordedAndLogsNoPersonalData")
    void dispute_sourceFails_isTracedFailedRecordedAndLogsNoPersonalData(CapturedOutput output) {
        adapter.failingTheDispute(new IllegalStateException("source said 00000001R SIM-1 Lucía is unknown"));
        HolderRef holder = checkedJourney();

        service.dispute(CGCOM, holder, "r1");

        assertThat(section(holder).requests().getFirst().status()).isEqualTo(EntitlementRecord.Status.UNDER_REVIEW);
        assertThat(trail.disputes()).singleElement().satisfies(event ->
                assertThat(event.result()).isEqualTo(AuthenticSourceAuditTrail.DisputeResult.FAILED));
        assertThat(output.getAll()).contains("failed: IllegalStateException")
                .doesNotContain("00000001R", "SIM-1", "Lucía", "Prueba Sintética");
    }

    @Test
    @DisplayName("AC-06 — dispute_attestationTypeWithoutSource_isTracedNotSupportedAndRecorded")
    void dispute_attestationTypeWithoutSource_isTracedNotSupportedAndRecorded() {
        HolderRef holder = journeyWithDni("00000001R");
        rows.put(CGCOM, new AuthenticSourceConfig("employee.sd.1", Set.of(), Map.of()));
        service.check(CGCOM, holder);

        service.dispute(CGCOM, holder, "r1");

        assertThat(section(holder).requests().getFirst().status()).isEqualTo(EntitlementRecord.Status.UNDER_REVIEW);
        assertThat(trail.disputes()).singleElement().satisfies(event -> {
            assertThat(event.result()).isEqualTo(AuthenticSourceAuditTrail.DisputeResult.NOT_SUPPORTED);
            assertThat(event.adapter()).isEqualTo("none");
        });
    }

    @Test
    @DisplayName("AC-06 — dispute_configurationBroken_isTracedFailedAndRecorded")
    void dispute_configurationBroken_isTracedFailedAndRecorded() {
        HolderRef holder = checkedJourney();
        rows.remove(CGCOM);

        service.dispute(CGCOM, holder, "r1");

        assertThat(section(holder).requests().getFirst().status()).isEqualTo(EntitlementRecord.Status.UNDER_REVIEW);
        assertThat(trail.disputes()).singleElement().satisfies(event ->
                assertThat(event.result()).isEqualTo(AuthenticSourceAuditTrail.DisputeResult.FAILED));
    }

    @Test
    @DisplayName("AC-06 — dispute_traceFails_isRecordedAnyway")
    void dispute_traceFails_isRecordedAnyway() {
        HolderRef holder = checkedJourney();
        EntitlementService failingTrail = new EntitlementService(journey, journey, journey, sessions,
                new AuthenticSourceConfigs(tenant -> Optional.ofNullable(rows.get(tenant)),
                        new AdapterRegistry(List.of(adapter))),
                new AuthenticSourceAuditTrail() {
                    @Override
                    public void append(TenantId tenant, Event event) {
                    }

                    @Override
                    public void appendDispute(TenantId tenant, DisputeEvent event) {
                        throw new IllegalStateException("log sink down");
                    }
                }, TransactionOperations.withoutTransaction(), clock, new LookupSlots(100, 100), (t, j, r) -> false);

        failingTrail.dispute(CGCOM, holder, "r1");

        assertThat(section(holder).requests().getFirst().status()).isEqualTo(EntitlementRecord.Status.UNDER_REVIEW);
        failingTrail.close();
    }

    @Test
    @DisplayName("EC-02 — dispute_thenCheckAgain_requestStaysUnderReviewAndCannotBeConfirmed")
    void dispute_thenCheckAgain_requestStaysUnderReviewAndCannotBeConfirmed() {
        adapter.answering(searched -> SourceRequests.of(request("SIM-A", RequestStatus.AVAILABLE, Map.of()),
                request("SIM-B", RequestStatus.AVAILABLE, Map.of())));
        HolderRef holder = journeyWithDni("00000001R");
        service.check(CGCOM, holder);
        service.dispute(CGCOM, holder, "r1");

        EntitlementService.Outcome outcome = service.check(CGCOM, holder);

        assertThat(outcome).isInstanceOf(EntitlementService.Outcome.Confirmed.class);
        assertThat(outcome.requests()).extracting(EntitlementService.CheckedRequest::id,
                        EntitlementService.CheckedRequest::status)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("r1", RequestStatus.UNDER_REVIEW),
                        org.assertj.core.groups.Tuple.tuple("r2", RequestStatus.AVAILABLE));
    }

    /** A journey opened for a new holder, as {@code POST /journey} opens it, with a default identity. */
    private HolderRef startJourney(TenantId tenant) {
        HolderRef holder = Holders.next();
        journey.open(tenant, holder, new HolderIdentity("Lucia", "Prueba Sintetica",
                new es.in2.issuanceportal.session.api.PersonalIdentifier(
                es.in2.issuanceportal.session.api.IdentifierType.PERSONAL_ID, "00000001R"), null), null);
        return holder;
    }
}
