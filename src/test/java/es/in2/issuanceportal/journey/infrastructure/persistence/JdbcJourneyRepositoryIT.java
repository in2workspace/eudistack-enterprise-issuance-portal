package es.in2.issuanceportal.journey.infrastructure.persistence;

import es.in2.issuanceportal.TestcontainersConfiguration;
import es.in2.issuanceportal.journey.application.port.JourneyRepository;
import es.in2.issuanceportal.journey.domain.Journey;
import es.in2.issuanceportal.journey.domain.JourneyState;
import es.in2.issuanceportal.journey.domain.JourneyStep;
import es.in2.issuanceportal.journey.domain.WithdrawalReason;
import es.in2.issuanceportal.session.api.SessionId;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JdbcJourneyRepositoryIT {

    private static final TenantId SANDBOX = new TenantId("sandbox");
    private static final TenantId KPMG = new TenantId("kpmg");

    @Autowired
    private JourneyRepository repository;
    @Autowired
    private JdbcClient jdbc;

    @Test
    void findById_insertedJourney_returnsIt() {
        Journey journey = Journey.start(UUID.randomUUID(), now());

        repository.insert(SANDBOX, journey);

        Journey read = repository.findById(SANDBOX, journey.id()).orElseThrow();
        assertThat(read.state()).isEqualTo(JourneyState.IN_PROGRESS);
        assertThat(read.step()).isEqualTo(JourneyStep.STARTED);
        assertThat(read.createdAt()).isEqualTo(journey.createdAt());
        assertThat(read.issuanceProcessIds()).isEmpty();
    }

    @Test
    void update_withdrawnJourney_savesStateStepReasonAndEnd() {
        Journey journey = Journey.start(UUID.randomUUID(), now());
        repository.insert(SANDBOX, journey);
        journey.reach(JourneyStep.ENTITLEMENT_CONFIRMED, now());
        journey.withdraw(WithdrawalReason.CANCELLED, now());

        assertThat(repository.update(SANDBOX, journey, JourneyState.IN_PROGRESS)).isTrue();

        Journey read = repository.findById(SANDBOX, journey.id()).orElseThrow();
        assertThat(read.state()).isEqualTo(JourneyState.WITHDRAWN);
        assertThat(read.step()).isEqualTo(JourneyStep.ENTITLEMENT_CONFIRMED);
        assertThat(read.withdrawalReason()).contains(WithdrawalReason.CANCELLED);
        assertThat(read.endedAt()).isPresent();
    }

    @Test
    void update_journeyChangedSinceRead_returnsFalseAndKeepsFirstChange() {
        Journey journey = Journey.start(UUID.randomUUID(), now());
        repository.insert(SANDBOX, journey);
        Journey parallelCopy = repository.findById(SANDBOX, journey.id()).orElseThrow();
        journey.withdraw(WithdrawalReason.EXPIRED, now());
        repository.update(SANDBOX, journey, JourneyState.IN_PROGRESS);

        parallelCopy.withdraw(WithdrawalReason.CANCELLED, now());

        assertThat(repository.update(SANDBOX, parallelCopy, JourneyState.IN_PROGRESS)).isFalse();
        assertThat(repository.findById(SANDBOX, journey.id()).orElseThrow().withdrawalReason())
                .contains(WithdrawalReason.EXPIRED);
    }

    @Test
    void findById_journeyOfAnotherTenant_returnsEmpty() {
        Journey journey = Journey.start(UUID.randomUUID(), now());
        repository.insert(KPMG, journey);

        assertThat(repository.findById(SANDBOX, journey.id())).isEmpty();
    }

    @Test
    void findExpiryCandidates_openSessions_returnsOnlyInactiveOrWithdrawnOnes() {
        Instant cutoff = now().minus(Duration.ofMinutes(15));
        UUID inactive = journeyWithSession(cutoff.minusSeconds(60), false);
        UUID atTheLimit = journeyWithSession(cutoff, false);
        UUID active = journeyWithSession(now(), false);
        UUID withdrawnWithSession = journeyWithSession(now(), true);

        var candidates = repository.findExpiryCandidates(SANDBOX, cutoff, new UUID(0L, 0L), 1_000);

        assertThat(candidates).contains(inactive, atTheLimit, withdrawnWithSession).doesNotContain(active);
    }

    @Test
    void findExpiryCandidates_afterAnId_returnsOnlyLaterIdsInOrder() {
        Instant cutoff = now().minus(Duration.ofMinutes(15));
        journeyWithSession(cutoff.minusSeconds(60), false);
        journeyWithSession(cutoff.minusSeconds(60), false);
        List<UUID> all = repository.findExpiryCandidates(SANDBOX, cutoff, new UUID(0L, 0L), 1_000);

        List<UUID> rest = repository.findExpiryCandidates(SANDBOX, cutoff, all.getFirst(), 1_000);

        assertThat(rest).isEqualTo(all.subList(1, all.size()));
    }

    @Test
    void updateIfInactive_journeyUsedAfterTheRead_writesNothing() {
        Instant cutoff = now().minus(Duration.ofMinutes(15));
        UUID id = journeyWithSession(cutoff.minusSeconds(60), false);
        Journey staleRead = repository.findById(SANDBOX, id).orElseThrow();
        Journey used = repository.findById(SANDBOX, id).orElseThrow();
        used.touch(now());
        repository.update(SANDBOX, used, JourneyState.IN_PROGRESS);
        staleRead.withdraw(WithdrawalReason.EXPIRED, now());

        assertThat(repository.updateIfInactive(SANDBOX, staleRead, cutoff)).isFalse();
        assertThat(repository.deleteSessionIfClosedOrInactive(SANDBOX, id, cutoff)).isFalse();
        assertThat(repository.findById(SANDBOX, id).orElseThrow().state()).isEqualTo(JourneyState.IN_PROGRESS);
        assertThat(sessionRows(id)).isOne();
    }

    @Test
    void updateIfInactive_journeyStillInactive_withdrawsItAndItsSessionCanGo() {
        Instant cutoff = now().minus(Duration.ofMinutes(15));
        UUID id = journeyWithSession(cutoff.minusSeconds(60), false);
        Journey journey = repository.findById(SANDBOX, id).orElseThrow();
        journey.withdraw(WithdrawalReason.EXPIRED, now());

        assertThat(repository.updateIfInactive(SANDBOX, journey, cutoff)).isTrue();
        assertThat(repository.deleteSessionIfClosedOrInactive(SANDBOX, id, cutoff)).isTrue();
        assertThat(repository.findById(SANDBOX, id).orElseThrow().withdrawalReason())
                .contains(WithdrawalReason.EXPIRED);
        assertThat(sessionRows(id)).isZero();
    }

    /** A journey with its session, with the given last activity, optionally already withdrawn. */
    private UUID journeyWithSession(Instant lastActivity, boolean withdrawn) {
        Journey journey = Journey.start(UUID.randomUUID(), lastActivity);
        repository.insert(SANDBOX, journey);
        if (withdrawn) {
            journey.withdraw(WithdrawalReason.CANCELLED, now());
            repository.update(SANDBOX, journey, JourneyState.IN_PROGRESS);
        }
        jdbc.sql("INSERT INTO \"sandbox_issuance_portal\".journey_session (session_id_hash, journey_id) "
                        + "VALUES (:hash, :id)")
                .param("hash", SessionId.generate().hash())
                .param("id", journey.id())
                .update();
        return journey.id();
    }

    private long sessionRows(UUID journeyId) {
        return jdbc.sql("SELECT count(*) FROM \"sandbox_issuance_portal\".journey_session WHERE journey_id = :j")
                .param("j", journeyId).query(Long.class).single();
    }

    private static Instant now() {
        // PostgreSQL keeps microseconds.
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
