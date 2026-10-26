package es.in2.issuanceportal.journey.infrastructure.scheduling;

import es.in2.issuanceportal.TestcontainersConfiguration;
import es.in2.issuanceportal.journey.application.JourneyService;
import es.in2.issuanceportal.shared.tenant.TenantId;
import es.in2.issuanceportal.shared.tenant.TenantSchemas;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JourneyExpiryJobIT {

    private static final TenantId KPMG = new TenantId("kpmg");

    @Autowired
    private JourneyService service;
    @Autowired
    private TenantSchemas tenants;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private Clock clock;

    /** Each sweep holds the tenant's lock for 30 s: free it so no test depends on the order they run in. */
    @BeforeEach
    void releaseLock() {
        jdbc.sql("DELETE FROM \"kpmg_issuance_portal\".shedlock").update();
    }

    @Test
    @DisplayName("AC-04 — sweep_journeyInactiveForFifteenMinutes_withdrawsAsExpiredAndKeepsIt")
    void sweep_journeyInactiveForFifteenMinutes_withdrawsAsExpiredAndKeepsIt() {
        UUID journey = inactiveJourney();

        assertThat(job().sweep(KPMG)).isTrue();

        assertThat(journeyRow(journey))
                .containsEntry("state", "withdrawn")
                .containsEntry("withdrawal_reason", "expired");
        assertThat(sessionRows(journey)).isZero();
    }

    @Test
    @DisplayName("EC-05 — sweep_lockHeldByAnotherInstance_skipsTenant")
    void sweep_lockHeldByAnotherInstance_skipsTenant() {
        UUID journey = inactiveJourney();
        // Another instance holds the tenant's lock. The job uses database time, so lock_until does too.
        jdbc.sql("INSERT INTO \"kpmg_issuance_portal\".shedlock (name, lock_until, locked_at, locked_by) "
                        + "VALUES (:name, now() + interval '1 minute', now(), 'another-instance')")
                .param("name", JourneyExpiryJob.LOCK_NAME).update();

        assertThat(job().sweep(KPMG)).isFalse();

        assertThat(journeyRow(journey)).containsEntry("state", "in_progress");
        assertThat(sessionRows(journey)).isOne();
    }

    @Test
    void run_tenantDeactivatedWithJourneysInProgress_stillDeletesTheirPersonalData() {
        UUID journey = inactiveJourney();
        jdbc.sql("UPDATE public.tenant_registry SET status = 'inactive' WHERE schema_name = 'kpmg'").update();
        try {
            job().run();

            assertThat(sessionRows(journey)).isZero();
            assertThat(journeyRow(journey)).containsEntry("withdrawal_reason", "expired");
        } finally {
            jdbc.sql("UPDATE public.tenant_registry SET status = 'active' WHERE schema_name = 'kpmg'").update();
        }
    }

    private JourneyExpiryJob job() {
        return new JourneyExpiryJob(service, tenants, jdbcTemplate, clock);
    }

    private UUID inactiveJourney() {
        JourneyService.Started started = service.start(KPMG, Optional.empty());
        UUID id = started.journey().id();
        jdbc.sql("UPDATE \"kpmg_issuance_portal\".journey SET last_activity = now() - interval '16 minutes' WHERE id = :id")
                .param("id", id).update();
        return id;
    }

    private long sessionRows(UUID journeyId) {
        return jdbc.sql("SELECT count(*) FROM \"kpmg_issuance_portal\".journey_session WHERE journey_id = :j")
                .param("j", journeyId).query(Long.class).single();
    }

    private Map<String, Object> journeyRow(UUID id) {
        return jdbc.sql("SELECT state, withdrawal_reason FROM \"kpmg_issuance_portal\".journey WHERE id = :id")
                .param("id", id).query().singleRow();
    }
}
