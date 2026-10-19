package es.in2.issuanceportal.journey.infrastructure.persistence;

import es.in2.issuanceportal.TestcontainersConfiguration;
import es.in2.issuanceportal.journey.application.port.JourneyRepository;
import es.in2.issuanceportal.journey.domain.Journey;
import es.in2.issuanceportal.journey.domain.JourneyState;
import es.in2.issuanceportal.journey.domain.JourneyStep;
import es.in2.issuanceportal.journey.domain.WithdrawalReason;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JdbcJourneyRepositoryIT {

    private static final TenantId SANDBOX = new TenantId("sandbox");
    private static final TenantId KPMG = new TenantId("kpmg");
    // Far in the past so journeys written by other tests never fall inside these cutoffs.
    private static final Instant T0 = Instant.parse("2001-01-01T10:00:00Z");

    @Autowired
    private JourneyRepository repository;

    @Test
    void readsBackAStartedJourney() {
        Journey journey = Journey.start(UUID.randomUUID(), now());

        repository.insert(SANDBOX, journey);

        Journey read = repository.findById(SANDBOX, journey.id()).orElseThrow();
        assertThat(read.state()).isEqualTo(JourneyState.IN_PROGRESS);
        assertThat(read.step()).isEqualTo(JourneyStep.STARTED);
        assertThat(read.createdAt()).isEqualTo(journey.createdAt());
        assertThat(read.issuanceProcessIds()).isEmpty();
    }

    @Test
    void savesAWithdrawal() {
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
    void anUpdateLosesAgainstWhoeverChangedTheJourneyFirst() {
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
    void findsOnlyInactiveJourneysInProgressOldestFirst() {
        Journey oldest = startedAt(T0);
        Journey older = startedAt(T0.plusSeconds(60));
        Journey recent = startedAt(T0.plus(Duration.ofMinutes(30)));
        Journey withdrawn = startedAt(T0);
        withdrawn.withdraw(WithdrawalReason.CANCELLED, T0);
        repository.update(SANDBOX, withdrawn, JourneyState.IN_PROGRESS);

        var found = repository.findInProgressInactiveSince(SANDBOX, T0.plus(Duration.ofMinutes(10)), 10);

        assertThat(found).extracting(Journey::id).containsExactly(oldest.id(), older.id())
                .doesNotContain(recent.id(), withdrawn.id());
    }

    @Test
    void aJourneyOfOneTenantDoesNotExistForAnother() {
        Journey journey = Journey.start(UUID.randomUUID(), now());
        repository.insert(KPMG, journey);

        assertThat(repository.findById(SANDBOX, journey.id())).isEmpty();
    }

    private Journey startedAt(Instant at) {
        Journey journey = Journey.start(UUID.randomUUID(), at);
        repository.insert(SANDBOX, journey);
        return journey;
    }

    private static Instant now() {
        // PostgreSQL keeps microseconds.
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
