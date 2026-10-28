package es.in2.issuanceportal.authenticsource.infrastructure.simulated;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.issuanceportal.authenticsource.domain.IdentifierType;
import es.in2.issuanceportal.authenticsource.domain.LookupResult;
import es.in2.issuanceportal.authenticsource.domain.PersonalIdentifier;
import es.in2.issuanceportal.authenticsource.domain.SourceRecord;
import es.in2.issuanceportal.authenticsource.domain.config.AuthenticSourceConfig;
import es.in2.issuanceportal.authenticsource.domain.config.SourceSettings;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulatedAuthenticSourceAdapterTest {

    private static final TenantId CGCOM = new TenantId("cgcom");
    private static final TenantId SANDBOX = new TenantId("sandbox");
    private static final String DOCTORID = "doctorid.sd.1";

    private final Map<TenantId, AuthenticSourceConfig> rows = new HashMap<>();
    private final SimulatedAuthenticSourceAdapter adapter =
            new SimulatedAuthenticSourceAdapter(tenant -> Optional.ofNullable(rows.get(tenant)), new ObjectMapper());

    private void save(TenantId tenant, List<Map<String, Object>> records) {
        rows.put(tenant, new AuthenticSourceConfig(DOCTORID, Set.of(IdentifierType.COLEGIADO), Map.of(DOCTORID,
                new SourceSettings("DoctorID", "simulated", null, null, null,
                        Set.of(IdentifierType.DNI, IdentifierType.COLEGIADO), List.of(), null,
                        Map.of("records", records)))));
    }

    private static Map<String, Object> record(String dni, String colegiado, boolean entitled) {
        return Map.of("dni", dni, "colegiado", colegiado, "entitled", entitled,
                "attributes", Map.of("registration_number", colegiado, "medical_boards", List.of("Madrid")));
    }

    private LookupResult search(TenantId tenant, IdentifierType type, String value) {
        return adapter.lookup(tenant, DOCTORID, new PersonalIdentifier(type, value), Duration.ofSeconds(10));
    }

    @ParameterizedTest
    @CsvSource({"DNI,00000001R", "COLEGIADO,280000001", "DNI,00000001r"})
    @DisplayName("AC-15 — lookup_existingRecord_isFoundByEitherIdentifierWithBoth")
    void lookup_existingRecord_isFoundByEitherIdentifierWithBoth(IdentifierType type, String value) {
        save(CGCOM, List.of(record("00000001R", "280000001", true)));

        LookupResult result = search(CGCOM, type, value);

        SourceRecord found = ((LookupResult.Found) result).record();
        assertThat(found.identifiers()).containsExactly(new PersonalIdentifier(IdentifierType.DNI, "00000001R"),
                new PersonalIdentifier(IdentifierType.COLEGIADO, "280000001"));
        assertThat(found.entitled()).isTrue();
        assertThat(found.attributes()).containsEntry("registration_number", "280000001");
    }

    @Test
    @DisplayName("AC-15 — lookup_deregistered_isFoundNotEntitled")
    void lookup_deregistered_isFoundNotEntitled() {
        save(CGCOM, List.of(record("00000002W", "280000002", false)));

        assertThat(((LookupResult.Found) search(CGCOM, IdentifierType.DNI, "00000002W")).record().entitled()).isFalse();
    }

    @Test
    @DisplayName("AC-15 — lookup_unknownDniOrRegistrationNumber_isNotFound")
    void lookup_unknownDniOrRegistrationNumber_isNotFound() {
        save(CGCOM, List.of(record("00000001R", "280000001", true)));

        assertThat(search(CGCOM, IdentifierType.DNI, "00000009Y")).isInstanceOf(LookupResult.NotFound.class);
        assertThat(search(CGCOM, IdentifierType.COLEGIADO, "289999999")).isInstanceOf(LookupResult.NotFound.class);
    }

    @Test
    void lookup_registrationNumberAsDni_isNotFound() {
        save(CGCOM, List.of(record("00000001R", "280000001", true)));

        assertThat(search(CGCOM, IdentifierType.DNI, "280000001")).isInstanceOf(LookupResult.NotFound.class);
    }

    @Test
    @DisplayName("EC-05 — lookup_recordOfAnotherTenant_isNotFound")
    void lookup_recordOfAnotherTenant_isNotFound() {
        save(CGCOM, List.of(record("00000001R", "280000001", true)));
        save(SANDBOX, List.of(record("00000005M", "280000005", true)));

        assertThat(search(SANDBOX, IdentifierType.DNI, "00000001R")).isInstanceOf(LookupResult.NotFound.class);
    }

    @Test
    @DisplayName("EC-06 — lookup_failingRecord_throws")
    void lookup_failingRecord_throws() {
        Map<String, Object> failing = new HashMap<>(record("00000004G", "280000004", true));
        failing.put("fail", true);
        save(CGCOM, List.of(failing));

        assertThatThrownBy(() -> search(CGCOM, IdentifierType.DNI, "00000004G"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("AC-09 — lookup_slowRecord_waitsUntilInterrupted")
    void lookup_slowRecord_waitsUntilInterrupted() throws Exception {
        Map<String, Object> slow = new HashMap<>(record("00000003A", "280000003", true));
        slow.put("delay_seconds", 30);
        save(CGCOM, List.of(slow));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var answer = executor.submit(() -> search(CGCOM, IdentifierType.DNI, "00000003A"));
            assertThatThrownBy(() -> answer.get(300, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            answer.cancel(true);
        }
    }

    @Test
    void lookup_malformedRecords_failsWithoutQuotingThem() {
        rows.put(CGCOM, new AuthenticSourceConfig(DOCTORID, Set.of(), Map.of(DOCTORID,
                new SourceSettings("DoctorID", "simulated", null, null, null, Set.of(IdentifierType.DNI), List.of(),
                        null, Map.of("records", "00000001R")))));

        assertThatThrownBy(() -> search(CGCOM, IdentifierType.DNI, "00000001R"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("settings.records of the simulated source is invalid");
    }

    @Test
    void lookup_noRecords_isNotFound() {
        rows.put(CGCOM, new AuthenticSourceConfig(DOCTORID, Set.of(), Map.of(DOCTORID,
                new SourceSettings("DoctorID", "simulated", null, null, null, Set.of(IdentifierType.DNI), List.of(),
                        null, null))));

        assertThat(search(CGCOM, IdentifierType.DNI, "00000001R"))
                .isInstanceOf(LookupResult.NotFound.class);
    }
}
