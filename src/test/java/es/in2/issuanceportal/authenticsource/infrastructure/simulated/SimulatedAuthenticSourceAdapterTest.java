package es.in2.issuanceportal.authenticsource.infrastructure.simulated;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.issuanceportal.authenticsource.domain.Holder;
import es.in2.issuanceportal.authenticsource.domain.IdentifierType;
import es.in2.issuanceportal.authenticsource.domain.MismatchedField;
import es.in2.issuanceportal.authenticsource.domain.PersonalIdentifier;
import es.in2.issuanceportal.authenticsource.domain.RequestStatus;
import es.in2.issuanceportal.authenticsource.domain.SourceRequest;
import es.in2.issuanceportal.authenticsource.domain.SourceRequests;
import es.in2.issuanceportal.authenticsource.domain.config.AuthenticSourceConfig;
import es.in2.issuanceportal.authenticsource.domain.config.SourceSettings;
import es.in2.issuanceportal.shared.tenant.TenantId;
import es.in2.issuanceportal.authenticsource.application.port.DisputeNotSupportedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulatedAuthenticSourceAdapterTest {

    private static final TenantId CGCOM = new TenantId("cgcom");
    private static final TenantId SANDBOX = new TenantId("sandbox");
    private static final String DOCTORID = "doctorid.sd.1";

    private final Map<TenantId, AuthenticSourceConfig> rows = new HashMap<>();
    private final SimulatedAuthenticSourceAdapter adapter = new SimulatedAuthenticSourceAdapter(new ObjectMapper());

    private void save(TenantId tenant, List<Map<String, Object>> people) {
        rows.put(tenant, new AuthenticSourceConfig(DOCTORID, Set.of(IdentifierType.COLEGIADO), Map.of(DOCTORID,
                new SourceSettings("DoctorID", "simulated", null, null, null,
                        Set.of(IdentifierType.PERSONAL_ID, IdentifierType.COLEGIADO), List.of(), null,
                        Map.of("people", people)))));
    }

    private static Map<String, Object> identifier(String type, String value) {
        return Map.of("type", type, "value", value);
    }

    private static Map<String, Object> request(String ref, String status) {
        return Map.of("source_ref", ref, "status", status, "renewal", false);
    }

    private static Map<String, Object> person(List<Map<String, Object>> requests) {
        Map<String, Object> person = new HashMap<>();
        person.put("identifiers", List.of(identifier("personal_id", "00000001R"), identifier("colegiado", "280000001")));
        person.put("requests", requests);
        return person;
    }

    private SourceRequests search(TenantId tenant, IdentifierType type, String value) {
        return adapter.lookup(tenant, DOCTORID, holder(type, value), settings(tenant), Duration.ofSeconds(10));
    }

    private SourceSettings settings(TenantId tenant) {
        return rows.get(tenant).source().orElseThrow();
    }

    private static Holder holder(IdentifierType type, String value) {
        return new Holder("Cualquiera", "Otro Nombre", new PersonalIdentifier(type, value));
    }

    @Test
    @DisplayName("AC-06 — dispute_personWithoutFlags_isAccepted")
    void dispute_personWithoutFlags_isAccepted() {
        save(CGCOM, List.of(person(List.of(request("SIM-1", "available")))));

        assertThat(adapter.supportsDispute(settings(CGCOM))).isTrue();
        assertThatNoException().isThrownBy(() -> adapter.dispute(CGCOM, DOCTORID,
                holder(IdentifierType.PERSONAL_ID, "00000001R"), "SIM-1", settings(CGCOM), Duration.ofSeconds(10)));
    }

    @Test
    @DisplayName("EC-09 — dispute_personWithDisputeSupportedFalse_isNotSupported")
    void dispute_personWithDisputeSupportedFalse_isNotSupported() {
        Map<String, Object> person = person(List.of(request("SIM-1", "available")));
        person.put("dispute_supported", false);
        save(CGCOM, List.of(person));

        Holder holder = holder(IdentifierType.PERSONAL_ID, "00000001R");
        SourceSettings settings = settings(CGCOM);
        Duration timeout = Duration.ofSeconds(10);

        assertThatThrownBy(() -> adapter.dispute(CGCOM, DOCTORID, holder, "SIM-1", settings, timeout))
                .isInstanceOf(DisputeNotSupportedException.class);
    }

    @Test
    @DisplayName("AC-06 — dispute_personWhoseDisputeFails_throws")
    void dispute_personWhoseDisputeFails_throws() {
        Map<String, Object> person = person(List.of(request("SIM-1", "available")));
        person.put("dispute_fail", true);
        save(CGCOM, List.of(person));

        Holder holder = holder(IdentifierType.PERSONAL_ID, "00000001R");
        SourceSettings settings = settings(CGCOM);
        Duration timeout = Duration.ofSeconds(10);

        assertThatThrownBy(() -> adapter.dispute(CGCOM, DOCTORID, holder, "SIM-1", settings, timeout))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @CsvSource({"PERSONAL_ID,00000001R", "COLEGIADO,280000001", "PERSONAL_ID,00000001r", "PERSONAL_ID,' 0000 0001R '"})
    @DisplayName("AC-01 — lookup_existingPerson_isFoundByEitherIdentifierIgnoringTheName")
    void lookup_existingPerson_isFoundByEitherIdentifierIgnoringTheName(IdentifierType type, String value) {
        save(CGCOM, List.of(person(List.of(request("SIM-1", "available")))));

        SourceRequests result = search(CGCOM, type, value);

        assertThat(((SourceRequests.Requests) result).requests()).singleElement()
                .satisfies(found -> assertThat(found.status()).isEqualTo(RequestStatus.AVAILABLE));
    }

    @Test
    @DisplayName("AC-01 — lookup_severalRequests_keepsTheOrderAndEveryField")
    void lookup_severalRequests_keepsTheOrderAndEveryField() {
        Map<String, Object> mismatch = new HashMap<>(request("SIM-2", "identity_mismatch"));
        mismatch.put("mismatched_fields", List.of("given_name", "family_names"));
        Map<String, Object> available = new HashMap<>(request("SIM-1", "available"));
        available.put("renewal", true);
        available.put("requested_by", "Secretaría");
        available.put("requested_at", "2026-10-01T09:00:00Z");
        available.put("display_identifier", "280000001");
        available.put("organization", "Colegio de Prueba");
        available.put("attributes", Map.of("medical_speciality", List.of("Cardiología", "Medicina interna")));
        save(CGCOM, List.of(person(List.of(mismatch, available))));

        List<SourceRequest> found = ((SourceRequests.Requests) search(CGCOM, IdentifierType.PERSONAL_ID,
                "00000001R")).requests();

        assertThat(found).extracting(SourceRequest::sourceRef).containsExactly("SIM-2", "SIM-1");
        assertThat(found.getFirst().status()).isEqualTo(RequestStatus.IDENTITY_MISMATCH);
        assertThat(found.getFirst().mismatchedFields())
                .containsExactly(MismatchedField.GIVEN_NAME, MismatchedField.FAMILY_NAMES);
        SourceRequest second = found.get(1);
        assertThat(second.renewal()).isTrue();
        assertThat(second.requestedBy()).isEqualTo("Secretaría");
        assertThat(second.requestedAt()).isEqualTo(Instant.parse("2026-10-01T09:00:00Z"));
        assertThat(second.displayIdentifier()).isEqualTo("280000001");
        assertThat(second.organization()).isEqualTo("Colegio de Prueba");
        assertThat(second.attributes()).containsEntry("medical_speciality", List.of("Cardiología", "Medicina interna"));
    }

    @Test
    @DisplayName("AC-03 — lookup_personWithoutRequests_isNoRequests")
    void lookup_personWithoutRequests_isNoRequests() {
        save(CGCOM, List.of(person(List.of())));

        assertThat(search(CGCOM, IdentifierType.PERSONAL_ID, "00000001R")).isInstanceOf(SourceRequests.NoRequests.class);
    }

    @Test
    @DisplayName("AC-03 — lookup_personMarkedNotFound_isNotFound")
    void lookup_personMarkedNotFound_isNotFound() {
        Map<String, Object> person = person(List.of(request("SIM-1", "available")));
        person.put("not_found", true);
        save(CGCOM, List.of(person));

        assertThat(search(CGCOM, IdentifierType.PERSONAL_ID, "00000001R")).isInstanceOf(SourceRequests.NotFound.class);
    }

    @Test
    @DisplayName("AC-03 — lookup_unknownIdentifier_isNotFound")
    void lookup_unknownIdentifier_isNotFound() {
        save(CGCOM, List.of(person(List.of(request("SIM-1", "available")))));

        assertThat(search(CGCOM, IdentifierType.PERSONAL_ID, "00000009D")).isInstanceOf(SourceRequests.NotFound.class);
        assertThat(search(CGCOM, IdentifierType.COLEGIADO, "289999999")).isInstanceOf(SourceRequests.NotFound.class);
    }

    @Test
    void lookup_registrationNumberAsPersonalId_isNotFound() {
        save(CGCOM, List.of(person(List.of(request("SIM-1", "available")))));

        assertThat(search(CGCOM, IdentifierType.PERSONAL_ID, "280000001")).isInstanceOf(SourceRequests.NotFound.class);
    }

    @Test
    @DisplayName("EC-05 — lookup_personOfAnotherTenant_isNotFound")
    void lookup_personOfAnotherTenant_isNotFound() {
        save(CGCOM, List.of(person(List.of(request("SIM-1", "available")))));
        save(SANDBOX, List.of());

        assertThat(search(SANDBOX, IdentifierType.PERSONAL_ID, "00000001R")).isInstanceOf(SourceRequests.NotFound.class);
    }

    @Test
    @DisplayName("EC-06 — lookup_failingPerson_throws")
    void lookup_failingPerson_throws() {
        Map<String, Object> failing = person(List.of(request("SIM-1", "available")));
        failing.put("fail", true);
        save(CGCOM, List.of(failing));

        assertThatThrownBy(() -> search(CGCOM, IdentifierType.PERSONAL_ID, "00000001R"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("AC-18 — lookup_slowPerson_waitsUntilInterrupted")
    void lookup_slowPerson_waitsUntilInterrupted() {
        Map<String, Object> slow = person(List.of(request("SIM-1", "available")));
        slow.put("delay_seconds", 30);
        save(CGCOM, List.of(slow));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var answer = executor.submit(() -> search(CGCOM, IdentifierType.PERSONAL_ID, "00000001R"));
            assertThatThrownBy(() -> answer.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            answer.cancel(true);
        }
    }

    @Test
    void lookup_malformedPeople_failsWithoutQuotingThem() {
        rows.put(CGCOM, new AuthenticSourceConfig(DOCTORID, Set.of(), Map.of(DOCTORID,
                new SourceSettings("DoctorID", "simulated", null, null, null, Set.of(IdentifierType.PERSONAL_ID), List.of(),
                        null, Map.of("people", "00000001R")))));

        assertThatThrownBy(() -> search(CGCOM, IdentifierType.PERSONAL_ID, "00000001R"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("settings.people of the simulated source is invalid");
    }

    @Test
    void lookup_requestWithAnUnknownStatus_failsWithoutQuotingIt() {
        save(CGCOM, List.of(person(List.of(request("SIM-1", "gone_wrong")))));

        assertThatThrownBy(() -> search(CGCOM, IdentifierType.PERSONAL_ID, "00000001R"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("settings.people of the simulated source is invalid")
                .hasMessageNotContaining("SIM-1");
    }

    @Test
    void lookup_requestUnderReviewInTheConfiguration_isInvalid() {
        save(CGCOM, List.of(person(List.of(request("SIM-1", "under_review")))));

        assertThatThrownBy(() -> search(CGCOM, IdentifierType.PERSONAL_ID, "00000001R"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lookup_oldRecordsFormat_isNotRead() {
        rows.put(CGCOM, new AuthenticSourceConfig(DOCTORID, Set.of(), Map.of(DOCTORID,
                new SourceSettings("DoctorID", "simulated", null, null, null, Set.of(IdentifierType.PERSONAL_ID), List.of(),
                        null, Map.of("records", List.of(Map.of("personal_id", "00000001R", "entitled", true)))))));

        assertThat(search(CGCOM, IdentifierType.PERSONAL_ID, "00000001R")).isInstanceOf(SourceRequests.NotFound.class);
    }

    @Test
    void lookup_noPeople_isNotFound() {
        rows.put(CGCOM, new AuthenticSourceConfig(DOCTORID, Set.of(), Map.of(DOCTORID,
                new SourceSettings("DoctorID", "simulated", null, null, null, Set.of(IdentifierType.PERSONAL_ID), List.of(),
                        null, null))));

        assertThat(search(CGCOM, IdentifierType.PERSONAL_ID, "00000001R")).isInstanceOf(SourceRequests.NotFound.class);
    }
}
