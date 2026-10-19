package es.in2.issuanceportal.journey.infrastructure.scheduling;

import es.in2.issuanceportal.TestcontainersConfiguration;
import es.in2.issuanceportal.journey.application.JourneyService;
import es.in2.issuanceportal.session.api.JourneySessions;
import es.in2.issuanceportal.shared.tenant.TenantId;
import es.in2.issuanceportal.shared.tenant.TenantRegistry;
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
    private JourneySessions sessions;
    @Autowired
    private TenantRegistry tenants;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private Clock clock;

    @Test
    void ac04AJourneyInactiveForFifteenMinutesIsWithdrawnAsExpiredAndKept() {
        UUID journey = inactiveJourney();

        assertThat(job().sweep(KPMG)).isTrue();

        assertThat(journeyRow(journey))
                .containsEntry("state", "withdrawn")
                .containsEntry("withdrawal_reason", "expired");
        assertThat(sessions.openJourneys(KPMG)).doesNotContain(journey);
    }

    @Test
    void ec05OnlyOneInstanceSweepsATenantAtATime() {
        JourneyExpiryJob first = job();
        JourneyExpiryJob second = job();
        // Free the lock left by any earlier sweep of this tenant in the suite.
        jdbc.sql("DELETE FROM \"kpmg_issuance_portal\".shedlock").update();
        UUID journey = inactiveJourney();

        boolean firstSwept = first.sweep(KPMG);
        boolean secondSwept = second.sweep(KPMG);

        assertThat(firstSwept).isTrue();
        assertThat(secondSwept).isFalse();
        assertThat(journeyRow(journey)).containsEntry("withdrawal_reason", "expired");
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

    private Map<String, Object> journeyRow(UUID id) {
        return jdbc.sql("SELECT state, withdrawal_reason FROM \"kpmg_issuance_portal\".journey WHERE id = :id")
                .param("id", id).query().singleRow();
    }
}
