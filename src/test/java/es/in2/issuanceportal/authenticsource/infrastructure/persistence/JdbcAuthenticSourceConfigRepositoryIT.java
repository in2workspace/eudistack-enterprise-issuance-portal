package es.in2.issuanceportal.authenticsource.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.issuanceportal.MutableClock;
import es.in2.issuanceportal.TestcontainersConfiguration;
import es.in2.issuanceportal.authenticsource.domain.IdentifierType;
import es.in2.issuanceportal.authenticsource.domain.config.AuthenticSourceConfig;
import es.in2.issuanceportal.authenticsource.domain.config.SourceSettings;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class JdbcAuthenticSourceConfigRepositoryIT {

    private static final TenantId SANDBOX = new TenantId("sandbox");
    private static final TenantId KPMG = new TenantId("kpmg");

    private static final String VALID = """
            {
              "wallet_url": "https://sandbox.stg.eudistack.net/wallet",
              "attestation_type": "doctorid.sd.1",
              "identifier_types": {"doctorid.sd.1": "colegiado"},
              "authentic_source": {
                "doctorid.sd.1": {
                  "type_name": "DoctorID",
                  "adapter": "simulated",
                  "timeout_seconds": 5,
                  "identifier_types": ["dni", "colegiado"],
                  "mapping": [
                    {"attribute": "registration_number", "claim": "personal_administrative_number", "label": "Número de colegiado"},
                    {"attribute": "entitled", "claim": "legally_entitled"}
                  ],
                  "email_attribute": "email",
                  "settings": {"records": [{"dni": "00000001R"}]}
                }
              }
            }""";

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ObjectMapper json;

    private JdbcAuthenticSourceConfigRepository repository;

    @BeforeEach
    void setUp() {
        clearRows();
        repository = new JdbcAuthenticSourceConfigRepository(jdbc, json,
                new MutableClock(Instant.parse("2026-10-28T10:00:00Z")), JdbcAuthenticSourceConfigRepository.CACHE_TTL);
    }

    /** The container is shared with the other integration tests, which expect no configuration. */
    @AfterEach
    void clearRows() {
        jdbc.sql("DELETE FROM \"sandbox_issuance_portal\".portal_config").update();
        jdbc.sql("DELETE FROM \"kpmg_issuance_portal\".portal_config").update();
    }

    @Test
    void find_validRow_returnsTheSourceOfTheAttestationType() {
        save(SANDBOX, VALID);

        AuthenticSourceConfig config = repository.find(SANDBOX).orElseThrow();
        SourceSettings source = config.source().orElseThrow();

        assertThat(config.loginIdentifierTypes()).containsExactlyInAnyOrder(IdentifierType.DNI, IdentifierType.COLEGIADO);
        assertThat(source.adapter()).isEqualTo("simulated");
        assertThat(source.timeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(source.identifierTypes()).containsExactlyInAnyOrder(IdentifierType.DNI, IdentifierType.COLEGIADO);
        assertThat(source.mapping()).hasSize(2);
        assertThat(source.mapping().get(1).shown()).isFalse();
        assertThat(source.emailAttribute()).isEqualTo("email");
        assertThat(source.adapterSettings()).containsEntry("records", List.of(Map.of("dni", "00000001R")));
    }

    @Test
    @DisplayName("AC-10 — find_rowWithoutAuthenticSource_hasNoSource")
    void find_rowWithoutAuthenticSource_hasNoSource() {
        save(SANDBOX, "{\"attestation_type\": \"doctorid.sd.1\"}");

        assertThat(repository.find(SANDBOX).orElseThrow().source()).isEmpty();
    }

    @Test
    @DisplayName("ES-03 — find_invalidSource_returnsEmpty")
    void find_invalidSource_returnsEmpty() {
        save(SANDBOX, VALID.replace("\"timeout_seconds\": 5", "\"timeout_seconds\": 30"));

        assertThat(repository.find(SANDBOX)).isEmpty();
    }

    /** A misspelled key would otherwise leave the type without a source, which confirms every holder. */
    @Test
    @DisplayName("ES-03 — find_sourceUnderAnotherAttestationType_returnsEmpty")
    void find_sourceUnderAnotherAttestationType_returnsEmpty() {
        save(SANDBOX, VALID.replace("\"doctorid.sd.1\": {", "\"doctorid.sd1\": {"));

        assertThat(repository.find(SANDBOX)).isEmpty();
    }

    @Test
    @DisplayName("ES-03 — find_unknownIdentifierType_returnsEmpty")
    void find_unknownIdentifierType_returnsEmpty() {
        save(SANDBOX, VALID.replace("[\"dni\", \"colegiado\"]", "[\"dni\", \"nif\"]"));

        assertThat(repository.find(SANDBOX)).isEmpty();
    }

    @Test
    @DisplayName("ES-03 — find_invalidThenFixed_isSeenAtOnce")
    void find_invalidThenFixed_isSeenAtOnce() {
        save(SANDBOX, VALID.replace("\"adapter\": \"simulated\",", ""));
        assertThat(repository.find(SANDBOX)).isEmpty();

        save(SANDBOX, VALID);

        assertThat(repository.find(SANDBOX)).isPresent();
    }

    @Test
    @DisplayName("EC-05 — find_twoTenants_eachGetsItsOwn")
    void find_twoTenants_eachGetsItsOwn() {
        save(SANDBOX, VALID);
        save(KPMG, "{\"attestation_type\": \"employee.sd.1\"}");

        assertThat(repository.find(SANDBOX).orElseThrow().source()).isPresent();
        assertThat(repository.find(KPMG).orElseThrow().source()).isEmpty();
    }

    private void save(TenantId tenant, String config) {
        jdbc.sql("INSERT INTO " + tenant.schema() + ".portal_config (tenant, config) VALUES (:tenant, CAST(:config AS jsonb)) "
                        + "ON CONFLICT (tenant) DO UPDATE SET config = EXCLUDED.config, updated_at = now()")
                .param("tenant", tenant.value())
                .param("config", config)
                .update();
    }

    @Test
    @DisplayName("AC-13, ES-03 — find_emptyMappingElement_isInvalidAndNamesTheField")
    void find_emptyMappingElement_isInvalidAndNamesTheField(CapturedOutput output) {
        save(SANDBOX, VALID.replace("\"mapping\": [", "\"mapping\": [null, "));

        assertThat(repository.find(SANDBOX)).isEmpty();
        assertThat(output.getOut())
                .contains("Authentic source configuration of tenant sandbox is invalid: "
                        + "authentic_source.doctorid.sd.1.mapping[0] is empty")
                .doesNotContain("00000001R");
    }

    @Test
    @DisplayName("AC-13 — find_invalidSource_logsTheFieldNeverTheValue")
    void find_invalidSource_logsTheFieldNeverTheValue(CapturedOutput output) {
        save(SANDBOX, VALID.replace("\"timeout_seconds\": 5", "\"timeout_seconds\": 30"));

        assertThat(repository.find(SANDBOX)).isEmpty();
        assertThat(output.getOut())
                .contains("authentic_source.doctorid.sd.1.timeout_seconds must be between 1 and 10")
                .doesNotContain("00000001R");
    }
}
