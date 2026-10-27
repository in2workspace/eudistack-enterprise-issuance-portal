package es.in2.issuanceportal.journey.application;

import es.in2.issuanceportal.MutableClock;
import es.in2.issuanceportal.journey.InMemoryJourneyRepository;
import es.in2.issuanceportal.journey.RecordingJourneyAuditTrail;
import es.in2.issuanceportal.journey.application.port.JourneyAuditTrail;
import es.in2.issuanceportal.journey.api.JourneyProgress;
import es.in2.issuanceportal.journey.api.JourneyWithdrawal;
import es.in2.issuanceportal.journey.domain.Journey;
import es.in2.issuanceportal.journey.domain.JourneyClosedException;
import es.in2.issuanceportal.journey.domain.JourneyState;
import es.in2.issuanceportal.journey.domain.JourneyStep;
import es.in2.issuanceportal.journey.domain.WithdrawalReason;
import es.in2.issuanceportal.session.InMemoryJourneySessions;
import es.in2.issuanceportal.session.api.Section;
import es.in2.issuanceportal.session.api.SessionId;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JourneyServiceTest {

    private static final TenantId SANDBOX = new TenantId("sandbox");

    private final InMemoryJourneySessions sessions = new InMemoryJourneySessions();
    private final InMemoryJourneyRepository journeys = new InMemoryJourneyRepository(sessions);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-19T10:00:00Z"));
    private final RecordingJourneyAuditTrail trail = new RecordingJourneyAuditTrail();
    private final JourneyService service =
            new JourneyService(journeys, sessions, trail, TransactionOperations.withoutTransaction(),
                TransactionOperations.withoutTransaction(), clock);

    @Test
    void start_noPreviousSession_createsJourneyInProgressWithSession() {
        JourneyService.Started started = service.start(SANDBOX, Optional.empty());

        assertThat(started.journey().state()).isEqualTo(JourneyState.IN_PROGRESS);
        assertThat(started.journey().step()).isEqualTo(JourneyStep.STARTED);
        assertThat(sessions.journeyOf(SANDBOX, started.session())).contains(started.journey().id());
    }

    @Test
    void start_previousJourneyInProgress_withdrawsItAsCancelled() {
        JourneyService.Started previous = service.start(SANDBOX, Optional.empty());

        JourneyService.Started next = service.start(SANDBOX, Optional.of(previous.session()));

        Journey withdrawn = stored(previous);
        assertThat(withdrawn.state()).isEqualTo(JourneyState.WITHDRAWN);
        assertThat(withdrawn.withdrawalReason()).contains(WithdrawalReason.CANCELLED);
        assertThat(sessions.journeyOf(SANDBOX, previous.session())).isEmpty();
        assertThat(next.session()).isNotEqualTo(previous.session());
    }

    @Test
    void cancel_atDataStep_withdrawsWithStepAndDeletesPersonalData() {
        JourneyService.Started started = service.start(SANDBOX, Optional.empty());
        sessions.write(SANDBOX, started.session(), Section.IDENTITY, "{\"givenName\":\"Ana\"}");
        service.reach(SANDBOX, started.session(), JourneyProgress.Step.ENTITLEMENT_CONFIRMED);

        Journey journey = service.cancel(SANDBOX, started.session());

        assertThat(journey.state()).isEqualTo(JourneyState.WITHDRAWN);
        assertThat(journey.step()).isEqualTo(JourneyStep.ENTITLEMENT_CONFIRMED);
        assertThat(journey.withdrawalReason()).contains(WithdrawalReason.CANCELLED);
        assertThat(sessions.journeyOf(SANDBOX, started.session())).isEmpty();
        assertThat(journeys.findById(SANDBOX, journey.id())).isPresent();
    }

    @Test
    void cancel_completedJourney_onlyClosesSession() {
        JourneyService.Started started = service.start(SANDBOX, Optional.empty());
        complete(started);

        Journey journey = service.cancel(SANDBOX, started.session());

        assertThat(journey.state()).isEqualTo(JourneyState.COMPLETED);
        assertThat(journey.step()).isEqualTo(JourneyStep.OFFER_OBTAINED);
        assertThat(stored(started).state()).isEqualTo(JourneyState.COMPLETED);
        assertThat(sessions.journeyOf(SANDBOX, started.session())).isEmpty();
    }

    @Test
    void withdraw_notEntitled_withdrawsKeepingStep() {
        JourneyService.Started started = service.start(SANDBOX, Optional.empty());
        service.reach(SANDBOX, started.session(), JourneyProgress.Step.IDENTIFIED);

        service.withdraw(SANDBOX, started.session(), JourneyWithdrawal.Cause.NOT_ENTITLED);

        Journey journey = stored(started);
        assertThat(journey.state()).isEqualTo(JourneyState.WITHDRAWN);
        assertThat(journey.step()).isEqualTo(JourneyStep.IDENTIFIED);
        assertThat(journey.withdrawalReason()).contains(WithdrawalReason.NOT_ENTITLED);
        assertThat(sessions.journeyOf(SANDBOX, started.session())).isEmpty();
        JourneyAuditTrail.Event withdrawn = trail.events().getLast();
        assertThat(withdrawn.kind()).isEqualTo(JourneyAuditTrail.Kind.WITHDRAWN);
        assertThat(withdrawn.step()).isEqualTo(JourneyStep.IDENTIFIED);
        assertThat(withdrawn.withdrawalReason()).contains(WithdrawalReason.NOT_ENTITLED);
    }

    @Test
    void start_previousJourneyAlreadyInactive_withdrawsItAsExpiredNotCancelled() {
        JourneyService.Started previous = service.start(SANDBOX, Optional.empty());
        clock.advance(Duration.ofMinutes(16));

        service.start(SANDBOX, Optional.of(previous.session()));

        assertThat(stored(previous).withdrawalReason()).contains(WithdrawalReason.EXPIRED);
        assertThat(sessions.journeyOf(SANDBOX, previous.session())).isEmpty();
        assertThat(trail.events())
                .filteredOn(event -> event.journeyId().equals(previous.journey().id())
                        && event.kind() == JourneyAuditTrail.Kind.WITHDRAWN)
                .singleElement()
                .satisfies(event -> assertThat(event.withdrawalReason()).contains(WithdrawalReason.EXPIRED));
    }

    @Test
    void expireInactive_sweptTwice_recordsOneWithdrawnEventPerJourney() {
        JourneyService.Started first = service.start(SANDBOX, Optional.empty());
        JourneyService.Started second = service.start(SANDBOX, Optional.empty());
        clock.advance(Duration.ofMinutes(15));

        service.expireInactive(SANDBOX);
        service.expireInactive(SANDBOX);

        for (JourneyService.Started started : List.of(first, second)) {
            assertThat(trail.events())
                    .filteredOn(event -> event.journeyId().equals(started.journey().id())
                            && event.kind() == JourneyAuditTrail.Kind.WITHDRAWN)
                    .hasSize(1);
        }
    }

    @Test
    void expireInactive_moreJourneysThanOneBatch_expiresThemAll() {
        int count = JourneyService.SWEEP_BATCH + 5;
        for (int i = 0; i < count; i++) {
            service.start(SANDBOX, Optional.empty());
        }
        clock.advance(Duration.ofMinutes(15));

        assertThat(service.expireInactive(SANDBOX)).isEqualTo(count);
        assertThat(sessions.openJourneys(SANDBOX)).isEmpty();
    }

    @Test
    void expireInactive_journeyUsedAfterTheSweepRead_isNotExpired() {
        JourneyService.Started started = service.start(SANDBOX, Optional.empty());
        clock.advance(Duration.ofMinutes(15));
        // The holder's request renews the activity between the sweep's read and its write.
        Journey used = stored(started);
        used.touch(clock.instant());
        journeys.update(SANDBOX, used, JourneyState.IN_PROGRESS);
        Journey staleRead = Journey.restore(used.id(), JourneyState.IN_PROGRESS, JourneyStep.STARTED, null, null,
                List.of(), used.createdAt(), used.createdAt(), null);
        staleRead.withdraw(WithdrawalReason.EXPIRED, clock.instant());

        assertThat(journeys.updateIfInactive(SANDBOX, staleRead, clock.instant().minus(Journey.INACTIVITY_LIMIT)))
                .isFalse();
        assertThat(journeys.deleteSessionIfClosedOrInactive(SANDBOX, used.id(),
                clock.instant().minus(Journey.INACTIVITY_LIMIT))).isFalse();
        assertThat(stored(started).state()).isEqualTo(JourneyState.IN_PROGRESS);
        assertThat(sessions.journeyOf(SANDBOX, started.session())).isPresent();
    }

    @Test
    void current_inactiveForFifteenMinutes_withdrawsAsExpiredAndThrowsJourneyClosed() {
        JourneyService.Started started = service.start(SANDBOX, Optional.empty());
        clock.advance(Duration.ofMinutes(16));

        assertJourneyClosed(() -> service.current(SANDBOX, started.session()));

        Journey journey = stored(started);
        assertThat(journey.state()).isEqualTo(JourneyState.WITHDRAWN);
        assertThat(journey.withdrawalReason()).contains(WithdrawalReason.EXPIRED);
        assertThat(sessions.journeyOf(SANDBOX, started.session())).isEmpty();
    }

    @Test
    void current_activityWithinLimit_pushesBackExpiry() {
        JourneyService.Started started = service.start(SANDBOX, Optional.empty());
        clock.advance(Duration.ofMinutes(14));
        service.current(SANDBOX, started.session());
        clock.advance(Duration.ofMinutes(14));

        assertThat(service.current(SANDBOX, started.session()).state()).isEqualTo(JourneyState.IN_PROGRESS);
    }

    @Test
    void current_closedOrUnknownSession_throwsJourneyClosed() {
        JourneyService.Started started = service.start(SANDBOX, Optional.empty());
        service.cancel(SANDBOX, started.session());

        assertJourneyClosed(() -> service.current(SANDBOX, started.session()));
        assertJourneyClosed(() -> service.cancel(SANDBOX, started.session()));
        assertJourneyClosed(() -> service.current(SANDBOX, SessionId.generate()));
    }

    @Test
    void withdraw_completedJourney_throwsJourneyClosed() {
        JourneyService.Started started = service.start(SANDBOX, Optional.empty());
        complete(started);

        assertJourneyClosed(() -> service.withdraw(SANDBOX, started.session(), JourneyWithdrawal.Cause.NOT_ENTITLED));
    }

    @Test
    void expireInactive_inactiveJourneyInProgress_withdrawsAsExpiredAndKeepsIt() {
        JourneyService.Started inactive = service.start(SANDBOX, Optional.empty());
        service.reach(SANDBOX, inactive.session(), JourneyProgress.Step.IDENTIFIED);
        clock.advance(Duration.ofMinutes(10));
        JourneyService.Started active = service.start(SANDBOX, Optional.empty());
        clock.advance(Duration.ofMinutes(6));

        assertThat(service.expireInactive(SANDBOX)).isEqualTo(1);

        Journey expired = stored(inactive);
        assertThat(expired.state()).isEqualTo(JourneyState.WITHDRAWN);
        assertThat(expired.step()).isEqualTo(JourneyStep.IDENTIFIED);
        assertThat(expired.withdrawalReason()).contains(WithdrawalReason.EXPIRED);
        assertThat(sessions.journeyOf(SANDBOX, inactive.session())).isEmpty();
        assertThat(stored(active).state()).isEqualTo(JourneyState.IN_PROGRESS);
        assertThat(sessions.journeyOf(SANDBOX, active.session())).isPresent();
    }

    @Test
    void expireInactive_inactiveCompletedJourney_onlyClosesSession() {
        JourneyService.Started started = service.start(SANDBOX, Optional.empty());
        complete(started);
        clock.advance(Duration.ofMinutes(15));

        assertThat(service.expireInactive(SANDBOX)).isEqualTo(1);

        assertThat(stored(started).state()).isEqualTo(JourneyState.COMPLETED);
        assertThat(sessions.journeyOf(SANDBOX, started.session())).isEmpty();
    }

    @Test
    void expireInactive_secondSweep_closesNothing() {
        service.start(SANDBOX, Optional.empty());
        clock.advance(Duration.ofMinutes(15));
        service.expireInactive(SANDBOX);

        assertThat(service.expireInactive(SANDBOX)).isZero();
    }

    @Test
    void stateChanges_fullJourney_recordEventWithStepReached() {
        JourneyService.Started started = service.start(SANDBOX, Optional.empty());
        service.reach(SANDBOX, started.session(), JourneyProgress.Step.IDENTIFIED);
        service.reach(SANDBOX, started.session(), JourneyProgress.Step.ENTITLEMENT_CONFIRMED);
        service.cancel(SANDBOX, started.session());

        assertThat(trail.events())
                .extracting(JourneyAuditTrail.Event::kind, JourneyAuditTrail.Event::state, JourneyAuditTrail.Event::step)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(JourneyAuditTrail.Kind.STARTED, JourneyState.IN_PROGRESS, JourneyStep.STARTED),
                        org.assertj.core.groups.Tuple.tuple(JourneyAuditTrail.Kind.STEP_REACHED, JourneyState.IN_PROGRESS, JourneyStep.IDENTIFIED),
                        org.assertj.core.groups.Tuple.tuple(JourneyAuditTrail.Kind.STEP_REACHED, JourneyState.IN_PROGRESS, JourneyStep.ENTITLEMENT_CONFIRMED),
                        org.assertj.core.groups.Tuple.tuple(JourneyAuditTrail.Kind.WITHDRAWN, JourneyState.WITHDRAWN, JourneyStep.ENTITLEMENT_CONFIRMED));
        assertThat(trail.events().getLast().withdrawalReason()).contains(WithdrawalReason.CANCELLED);
        assertThat(trail.events()).allSatisfy(event -> assertThat(event.journeyId()).isEqualTo(started.journey().id()));
    }

    @Test
    void currentAndReach_noStateChange_recordNoEvent() {
        JourneyService.Started started = service.start(SANDBOX, Optional.empty());
        service.reach(SANDBOX, started.session(), JourneyProgress.Step.ENTITLEMENT_CONFIRMED);
        int before = trail.events().size();

        service.current(SANDBOX, started.session());
        service.reach(SANDBOX, started.session(), JourneyProgress.Step.IDENTIFIED);

        assertThat(trail.events()).hasSize(before);
    }

    @Test
    void expireInactive_inactiveJourney_recordsWithdrawnEventWithExpiredReason() {
        service.start(SANDBOX, Optional.empty());
        clock.advance(Duration.ofMinutes(15));

        service.expireInactive(SANDBOX);

        assertThat(trail.events().getLast().kind()).isEqualTo(JourneyAuditTrail.Kind.WITHDRAWN);
        assertThat(trail.events().getLast().withdrawalReason()).contains(WithdrawalReason.EXPIRED);
    }

    @Test
    void startAndCancel_auditTrailUnavailable_completeNormally() {
        trail.failFromNowOn();

        JourneyService.Started started = service.start(SANDBOX, Optional.empty());
        Journey journey = service.cancel(SANDBOX, started.session());

        assertThat(journey.state()).isEqualTo(JourneyState.WITHDRAWN);
        assertThat(sessions.journeyOf(SANDBOX, started.session())).isEmpty();
    }

    @Test
    void expireInactive_oneJourneyFails_expiresTheOthers() {
        UUID[] poisoned = new UUID[1];
        InMemoryJourneyRepository failingOnOne = new InMemoryJourneyRepository(sessions) {
            @Override
            public Optional<Journey> findById(TenantId tenant, UUID id) {
                if (id.equals(poisoned[0])) {
                    throw new IllegalStateException("unreadable row");
                }
                return super.findById(tenant, id);
            }
        };
        JourneyService sweeping = new JourneyService(failingOnOne, sessions, trail,
                TransactionOperations.withoutTransaction(),
                TransactionOperations.withoutTransaction(), clock);
        JourneyService.Started bad = sweeping.start(SANDBOX, Optional.empty());
        JourneyService.Started good = sweeping.start(SANDBOX, Optional.empty());
        poisoned[0] = bad.journey().id();
        clock.advance(Duration.ofMinutes(15));

        assertThat(sweeping.expireInactive(SANDBOX)).isEqualTo(1);

        assertThat(sessions.journeyOf(SANDBOX, good.session())).isEmpty();
        assertThat(failingOnOne.findById(SANDBOX, good.journey().id()).orElseThrow().withdrawalReason())
                .contains(WithdrawalReason.EXPIRED);
    }

    @Test
    void start_previousJourneyClosedByParallelRequest_startsNewJourney() {
        boolean[] parallelWins = {false};
        InMemoryJourneyRepository racing = new InMemoryJourneyRepository(sessions) {
            @Override
            public boolean update(TenantId tenant, Journey journey, JourneyState readInState) {
                if (parallelWins[0] && journey.state() == JourneyState.WITHDRAWN) {
                    parallelWins[0] = false;
                    return false;
                }
                return super.update(tenant, journey, readInState);
            }
        };
        JourneyService starting = new JourneyService(racing, sessions, trail,
                TransactionOperations.withoutTransaction(),
                TransactionOperations.withoutTransaction(), clock);
        JourneyService.Started previous = starting.start(SANDBOX, Optional.empty());
        parallelWins[0] = true;

        JourneyService.Started next = starting.start(SANDBOX, Optional.of(previous.session()));

        assertThat(next.journey().state()).isEqualTo(JourneyState.IN_PROGRESS);
        assertThat(sessions.journeyOf(SANDBOX, next.session())).contains(next.journey().id());
    }

    private void complete(JourneyService.Started started) {
        Journey journey = stored(started);
        journey.complete(clock.instant());
        journeys.update(SANDBOX, journey, JourneyState.IN_PROGRESS);
    }

    private Journey stored(JourneyService.Started started) {
        return journeys.findById(SANDBOX, started.journey().id()).orElseThrow();
    }

    private static void assertJourneyClosed(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(JourneyClosedException.class);
    }
}
