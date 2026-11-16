package es.in2.issuanceportal.journey.infrastructure.persistence;

import es.in2.issuanceportal.TestcontainersConfiguration;
import es.in2.issuanceportal.journey.application.port.JourneyRepository;
import es.in2.issuanceportal.journey.domain.Journey;
import es.in2.issuanceportal.journey.domain.JourneyState;
import es.in2.issuanceportal.journey.domain.JourneyStep;
import es.in2.issuanceportal.journey.domain.WithdrawalReason;
import es.in2.issuanceportal.session.Holders;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JdbcJourneyRepositoryIT {

    private static final TenantId SANDBOX = new TenantId("sandbox");
    private static final TenantId KPMG = new TenantId("kpmg");

    @Autowired
    private JourneyRepository repository;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void findById_insertedJourney_returnsIt() {
        Journey journey = Journey.start(UUID.randomUUID(), now(), null);

        repository.insert(SANDBOX, journey);

        Journey read = repository.findById(SANDBOX, journey.id()).orElseThrow();
        assertThat(read.state()).isEqualTo(JourneyState.IN_PROGRESS);
        assertThat(read.step()).isEqualTo(JourneyStep.IDENTIFIED);
        assertThat(read.createdAt()).isEqualTo(journey.createdAt());
        assertThat(read.completedAt()).isEmpty();
        assertThat(read.endedAt()).isEmpty();
    }

    @Test
    @DisplayName("AC-25 — update_completedJourney_keepsItsCompletionAndAnEndOnlyWhenItEnds")
    void update_completedJourney_keepsItsCompletionAndAnEndOnlyWhenItEnds() {
        Journey journey = Journey.start(UUID.randomUUID(), now(), null);
        repository.insert(SANDBOX, journey);
        journey.complete(now());

        assertThat(repository.update(SANDBOX, journey, JourneyState.IN_PROGRESS)).isTrue();

        Journey completed = repository.findById(SANDBOX, journey.id()).orElseThrow();
        assertThat(completed.state()).isEqualTo(JourneyState.COMPLETED);
        assertThat(completed.completedAt()).contains(journey.completedAt().orElseThrow());
        assertThat(completed.endedAt()).isEmpty();
        completed.end(now());
        assertThat(repository.update(SANDBOX, completed, JourneyState.COMPLETED)).isTrue();
        Journey ended = repository.findById(SANDBOX, journey.id()).orElseThrow();
        assertThat(ended.state()).isEqualTo(JourneyState.COMPLETED);
        assertThat(ended.endedAt()).isPresent();
        assertThat(ended.isActive()).isFalse();
    }

    @Test
    @DisplayName("AC-25 — updateIfInactive_completedJourney_endsItAndItsSessionCanGo")
    void updateIfInactive_completedJourney_endsItAndItsSessionCanGo() {
        Instant cutoff = now().minus(Duration.ofMinutes(15));
        UUID id = journeyWithSession(cutoff.minusSeconds(60), false);
        Journey journey = repository.findById(SANDBOX, id).orElseThrow();
        journey.complete(cutoff.minusSeconds(60));
        repository.update(SANDBOX, journey, JourneyState.IN_PROGRESS);
        Journey completed = repository.findById(SANDBOX, id).orElseThrow();
        completed.end(now());

        assertThat(repository.findExpiryCandidates(SANDBOX, cutoff, new UUID(0L, 0L), 10)).contains(id);
        assertThat(repository.updateIfInactive(SANDBOX, completed, JourneyState.COMPLETED, cutoff)).isTrue();
        assertThat(repository.deleteSessionIfClosedOrInactive(SANDBOX, id, cutoff)).isTrue();

        Journey ended = repository.findById(SANDBOX, id).orElseThrow();
        assertThat(ended.state()).isEqualTo(JourneyState.COMPLETED);
        assertThat(ended.endedAt()).isPresent();
        assertThat(sessionRows(id)).isZero();
    }

    @Test
    void updateIfInactive_journeyAlreadyEnded_writesNothing() {
        Instant cutoff = now().minus(Duration.ofMinutes(15));
        UUID id = journeyWithSession(cutoff.minusSeconds(60), false);
        Journey journey = repository.findById(SANDBOX, id).orElseThrow();
        journey.complete(cutoff.minusSeconds(60));
        repository.update(SANDBOX, journey, JourneyState.IN_PROGRESS);
        Journey first = repository.findById(SANDBOX, id).orElseThrow();
        Journey second = repository.findById(SANDBOX, id).orElseThrow();
        first.end(now());
        second.end(now());

        assertThat(repository.updateIfInactive(SANDBOX, first, JourneyState.COMPLETED, cutoff)).isTrue();
        assertThat(repository.updateIfInactive(SANDBOX, second, JourneyState.COMPLETED, cutoff)).isFalse();
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

        assertThat(repository.updateIfInactive(SANDBOX, staleRead, JourneyState.IN_PROGRESS, cutoff)).isFalse();
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

        assertThat(repository.updateIfInactive(SANDBOX, journey, JourneyState.IN_PROGRESS, cutoff)).isTrue();
        assertThat(repository.deleteSessionIfClosedOrInactive(SANDBOX, id, cutoff)).isTrue();
        assertThat(repository.findById(SANDBOX, id).orElseThrow().withdrawalReason())
                .contains(WithdrawalReason.EXPIRED);
        assertThat(sessionRows(id)).isZero();
    }

    /** A journey with its session, with the given last activity, optionally already withdrawn. */
    private UUID journeyWithSession(Instant lastActivity, boolean withdrawn) {
        Journey journey = Journey.start(UUID.randomUUID(), lastActivity, null);
        repository.insert(SANDBOX, journey);
        if (withdrawn) {
            journey.withdraw(WithdrawalReason.CANCELLED, now());
            repository.update(SANDBOX, journey, JourneyState.IN_PROGRESS);
        }
        jdbc.sql("INSERT INTO \"sandbox_issuance_portal\".journey_session (journey_id, holder_ref) "
                        + "VALUES (:id, :holder)")
                .param("id", journey.id())
                .param("holder", Holders.next().value())
                .update();
        return journey.id();
    }

    private long sessionRows(UUID journeyId) {
        return jdbc.sql("SELECT count(*) FROM \"sandbox_issuance_portal\".journey_session WHERE journey_id = :j")
                .param("j", journeyId).query(Long.class).single();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private static Instant now() {
        // PostgreSQL keeps microseconds.
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
