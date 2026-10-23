package es.in2.issuanceportal.journey.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.issuanceportal.MutableClock;
import es.in2.issuanceportal.TestcontainersConfiguration;
import es.in2.issuanceportal.journey.domain.config.PortalConfig;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JdbcPortalConfigRepositoryIT {

    private static final TenantId SANDBOX = new TenantId("sandbox");
    private static final TenantId KPMG = new TenantId("kpmg");

    private static final String VALID = """
            {
              "texts": {"es": {"portal.app_name": "Portal de emisión"}},
              "legal_links": {"legal_notice": "https://example.org/aviso-legal", "privacy": "https://example.org/privacidad"},
              "support": {"email": "soporte@example.org", "phone": "900 000 000", "schedule": "L-V 9:00-18:00", "response_time": "48 horas"},
              "wallet_url": "https://sandbox.stg.eudistack.net/wallet",
              "attestation_type": "doctorid.sd.1",
              "data_placement": {"givenName": "identity.given_name"},
              "onboarding": {"enabled": true, "wallet_unit_service_url": "https://wallet-unit.internal"},
              "portal_client_id": "issuance-portal-sandbox",
              "identifier_types": {"doctorid.sd.1": "colegiado"},
              "branding": {"ignored": true}
            }""";

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private ObjectMapper json;

    private MutableClock clock;
    private JdbcPortalConfigRepository repository;

    @BeforeEach
    void setUp() {
        clearRows();
        clock = new MutableClock(Instant.parse("2026-10-23T10:00:00Z"));
        repository = new JdbcPortalConfigRepository(jdbc, json, clock, JdbcPortalConfigRepository.CACHE_TTL);
    }

    /** The container is shared with the other integration tests, which expect no configuration. */
    @AfterEach
    void clearRows() {
        jdbc.sql("DELETE FROM \"sandbox_issuance_portal\".portal_config").update();
        jdbc.sql("DELETE FROM \"kpmg_issuance_portal\".portal_config").update();
    }

    @Test
    @DisplayName("AC-02 — find_validRow_returnsPublicPartAndServiceSettings")
    void find_validRow_returnsPublicPartAndServiceSettings() {
        save(SANDBOX, VALID);

        PortalConfig config = repository.find(SANDBOX).orElseThrow();

        assertThat(config.walletUrl()).isEqualTo("https://sandbox.stg.eudistack.net/wallet");
        assertThat(config.support().responseTime()).isEqualTo("48 horas");
        assertThat(config.legalLinks().privacy()).isEqualTo("https://example.org/privacidad");
        assertThat(config.texts()).containsKey("es");
        assertThat(config.settings().attestationType()).isEqualTo("doctorid.sd.1");
        assertThat(config.settings().onboarding().enabled()).isTrue();
        assertThat(config.settings().portalClientId()).isEqualTo("issuance-portal-sandbox");
        assertThat(config.settings().identifierTypes()).containsEntry("doctorid.sd.1", "colegiado");
    }

    @Test
    @DisplayName("AC-01 — find_noRow_returnsEmpty")
    void find_noRow_returnsEmpty() {
        assertThat(repository.find(SANDBOX)).isEmpty();
    }

    @Test
    @DisplayName("AC-03 — find_walletUrlNotHttps_returnsEmpty")
    void find_walletUrlNotHttps_returnsEmpty() {
        save(SANDBOX, VALID.replace("https://sandbox.stg.eudistack.net/wallet", "http://sandbox.stg.eudistack.net/wallet"));

        assertThat(repository.find(SANDBOX)).isEmpty();
    }

    @Test
    @DisplayName("ES-01 — find_wrongTypeOrNotAnObject_returnsEmpty")
    void find_wrongTypeOrNotAnObject_returnsEmpty() {
        save(SANDBOX, VALID.replace("{\"es\": {\"portal.app_name\": \"Portal de emisión\"}}", "\"texts\""));
        assertThat(repository.find(SANDBOX)).isEmpty();

        save(SANDBOX, "[1, 2]");
        assertThat(repository.find(SANDBOX)).isEmpty();

        save(SANDBOX, "null");
        assertThat(repository.find(SANDBOX)).isEmpty();
    }

    @Test
    @DisplayName("EC-01 — find_rowChangedWithinFiveMinutes_returnsCachedUntilTheyPass")
    void find_rowChangedWithinFiveMinutes_returnsCachedUntilTheyPass() {
        save(SANDBOX, VALID);
        repository.find(SANDBOX);
        save(SANDBOX, VALID.replace("48 horas", "24 horas"));

        clock.advance(Duration.ofMinutes(4));
        assertThat(repository.find(SANDBOX).orElseThrow().support().responseTime()).isEqualTo("48 horas");

        clock.advance(Duration.ofMinutes(1));
        assertThat(repository.find(SANDBOX).orElseThrow().support().responseTime()).isEqualTo("24 horas");
    }

    @Test
    @DisplayName("EC-01 — find_missingThenLoaded_isSeenAtOnce")
    void find_missingThenLoaded_isSeenAtOnce() {
        assertThat(repository.find(SANDBOX)).isEmpty();

        save(SANDBOX, VALID);

        assertThat(repository.find(SANDBOX)).isPresent();
    }

    @Test
    @DisplayName("EC-01 — find_invalidThenFixed_isSeenAtOnce")
    void find_invalidThenFixed_isSeenAtOnce() {
        save(SANDBOX, VALID.replace("\"wallet_url\"", "\"no_wallet_url\""));
        assertThat(repository.find(SANDBOX)).isEmpty();

        save(SANDBOX, VALID);

        assertThat(repository.find(SANDBOX)).isPresent();
    }

    @Test
    @DisplayName("AC-09 — find_twoTenants_eachGetsItsOwn")
    void find_twoTenants_eachGetsItsOwn() {
        save(SANDBOX, VALID);
        save(KPMG, VALID.replace("https://sandbox.stg.eudistack.net/wallet", "https://kpmg.stg.eudistack.net/wallet"));

        assertThat(repository.find(SANDBOX).orElseThrow().walletUrl()).isEqualTo("https://sandbox.stg.eudistack.net/wallet");
        assertThat(repository.find(KPMG).orElseThrow().walletUrl()).isEqualTo("https://kpmg.stg.eudistack.net/wallet");
    }

    private void save(TenantId tenant, String config) {
        jdbc.sql("INSERT INTO " + tenant.schema() + ".portal_config (tenant, config) VALUES (:tenant, CAST(:config AS jsonb)) "
                        + "ON CONFLICT (tenant) DO UPDATE SET config = EXCLUDED.config, updated_at = now()")
                .param("tenant", tenant.value())
                .param("config", config)
                .update();
    }
}
