package es.in2.issuanceportal.shared.tenant;

import es.in2.issuanceportal.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TenantSchemaMigratorIT {

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private TenantSchemaMigrator migrator;

    @Test
    void everyActiveTenantHasItsOwnSchemaAndNoOtherDoes() {
        assertThat(jdbc.sql("SELECT schema_name FROM information_schema.schemata WHERE schema_name LIKE '%_issuance_portal'")
                .query(String.class).list())
                .containsExactlyInAnyOrder("sandbox_issuance_portal", "kpmg_issuance_portal");
    }

    @Test
    void eachTenantSchemaHasTheJourneySessionAndLockTables() {
        assertThat(jdbc.sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'sandbox_issuance_portal'")
                .query(String.class).list())
                .contains("journey", "journey_session", "shedlock");
    }

    @Test
    void migratingAgainChangesNothing() {
        assertThatNoException().isThrownBy(() -> migrator.migrate(new TenantId("sandbox")));
    }

    @Test
    void aWithdrawnJourneyNeedsAReason() {
        assertThatThrownBy(() -> insertJourney("withdrawn", null, "now()"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aJourneyInProgressHasNoReasonAndNoEnd() {
        assertThatThrownBy(() -> insertJourney("in_progress", "cancelled", "NULL"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertJourney("in_progress", null, "now()"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onlyTheStatesOfTheContractAreAccepted() {
        assertThatThrownBy(() -> insertJourney("deleted", null, "now()"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aValidWithdrawnJourneyIsAccepted() {
        assertThatNoException().isThrownBy(() -> insertJourney("withdrawn", "expired", "now()"));
    }

    private void insertJourney(String state, String reason, String endedAt) {
        jdbc.sql("INSERT INTO \"sandbox_issuance_portal\".journey "
                        + "(id, state, step, withdrawal_reason, created_at, last_activity, ended_at) "
                        + "VALUES (:id, :state, 'started', :reason, now(), now(), " + endedAt + ")")
                .param("id", UUID.randomUUID())
                .param("state", state)
                .param("reason", reason)
                .update();
    }
}
