package es.in2.issuanceportal.journey.application;

import es.in2.issuanceportal.MutableClock;
import es.in2.issuanceportal.journey.InMemoryJourneyRepository;
import es.in2.issuanceportal.journey.api.JourneyProgress;
import es.in2.issuanceportal.journey.api.JourneyWithdrawal;
import es.in2.issuanceportal.journey.domain.Journey;
import es.in2.issuanceportal.journey.domain.JourneyState;
import es.in2.issuanceportal.journey.domain.JourneyStep;
import es.in2.issuanceportal.journey.domain.WithdrawalReason;
import es.in2.issuanceportal.session.InMemoryJourneySessions;
import es.in2.issuanceportal.session.api.Section;
import es.in2.issuanceportal.session.api.SessionId;
import es.in2.issuanceportal.shared.error.ProblemCode;
import es.in2.issuanceportal.shared.error.ProblemException;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JourneyServiceTest {

    private static final TenantId SANDBOX = new TenantId("sandbox");

    private final InMemoryJourneyRepository journeys = new InMemoryJourneyRepository();
    private final InMemoryJourneySessions sessions = new InMemoryJourneySessions();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-19T10:00:00Z"));
    private final JourneyService service = new JourneyService(journeys, sessions, clock);

    @Test
    void startCreatesAJourneyInProgressWithItsSession() {
        JourneyService.Started started = service.start(SANDBOX, Optional.empty());

        assertThat(started.journey().state()).isEqualTo(JourneyState.IN_PROGRESS);
        assertThat(started.journey().step()).isEqualTo(JourneyStep.STARTED);
        assertThat(sessions.journeyOf(SANDBOX, started.session())).contains(started.journey().id());
    }

    @Test
    void startingAgainWithdrawsThePreviousJourneyOfTheBrowser() {
        JourneyService.Started previous = service.start(SANDBOX, Optional.empty());

        JourneyService.Started next = service.start(SANDBOX, Optional.of(previous.session()));

        Journey withdrawn = stored(previous);
        assertThat(withdrawn.state()).isEqualTo(JourneyState.WITHDRAWN);
        assertThat(withdrawn.withdrawalReason()).contains(WithdrawalReason.CANCELLED);
        assertThat(sessions.journeyOf(SANDBOX, previous.session())).isEmpty();
        assertThat(next.session()).isNotEqualTo(previous.session());
    }

    @Test
    void cancellingAtTheDataStepWithdrawsWithThatStepAndDeletesThePersonalData() {
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
    void cancellingACompletedJourneyOnlyClosesItsSession() {
        JourneyService.Started started = service.start(SANDBOX, Optional.empty());
        complete(started);

        Journey journey = service.cancel(SANDBOX, started.session());

        assertThat(journey.state()).isEqualTo(JourneyState.COMPLETED);
        assertThat(journey.step()).isEqualTo(JourneyStep.OFFER_OBTAINED);
        assertThat(stored(started).state()).isEqualTo(JourneyState.COMPLETED);
        assertThat(sessions.journeyOf(SANDBOX, started.session())).isEmpty();
    }

    @Test
    void anotherModuleWithdrawsAsNotEntitledKeepingTheStep() {
        JourneyService.Started started = service.start(SANDBOX, Optional.empty());
        service.reach(SANDBOX, started.session(), JourneyProgress.Step.IDENTIFIED);

        service.withdraw(SANDBOX, started.session(), JourneyWithdrawal.Cause.NOT_ENTITLED);

        Journey journey = stored(started);
        assertThat(journey.state()).isEqualTo(JourneyState.WITHDRAWN);
        assertThat(journey.step()).isEqualTo(JourneyStep.IDENTIFIED);
        assertThat(journey.withdrawalReason()).contains(WithdrawalReason.NOT_ENTITLED);
        assertThat(sessions.journeyOf(SANDBOX, started.session())).isEmpty();
    }

    @Test
    void aStaleSessionIsClosedOnTheSpotEvenBeforeTheExpiryJob() {
        JourneyService.Started started = service.start(SANDBOX, Optional.empty());
        clock.advance(Duration.ofMinutes(16));

        assertJourneyClosed(() -> service.current(SANDBOX, started.session()));

        Journey journey = stored(started);
        assertThat(journey.state()).isEqualTo(JourneyState.WITHDRAWN);
        assertThat(journey.withdrawalReason()).contains(WithdrawalReason.EXPIRED);
        assertThat(sessions.journeyOf(SANDBOX, started.session())).isEmpty();
    }

    @Test
    void activityPushesBackTheExpiry() {
        JourneyService.Started started = service.start(SANDBOX, Optional.empty());
        clock.advance(Duration.ofMinutes(14));
        service.current(SANDBOX, started.session());
        clock.advance(Duration.ofMinutes(14));

        assertThat(service.current(SANDBOX, started.session()).state()).isEqualTo(JourneyState.IN_PROGRESS);
    }

    @Test
    void aClosedOrUnknownSessionIsAClosedJourney() {
        JourneyService.Started started = service.start(SANDBOX, Optional.empty());
        service.cancel(SANDBOX, started.session());

        assertJourneyClosed(() -> service.current(SANDBOX, started.session()));
        assertJourneyClosed(() -> service.cancel(SANDBOX, started.session()));
        assertJourneyClosed(() -> service.current(SANDBOX, SessionId.generate()));
    }

    @Test
    void aCompletedJourneyCannotBeWithdrawnByAnotherModule() {
        JourneyService.Started started = service.start(SANDBOX, Optional.empty());
        complete(started);

        assertJourneyClosed(() -> service.withdraw(SANDBOX, started.session(), JourneyWithdrawal.Cause.NOT_ENTITLED));
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
        assertThatThrownBy(call)
                .isInstanceOf(ProblemException.class)
                .extracting(e -> ((ProblemException) e).problem())
                .isEqualTo(ProblemCode.JOURNEY_CLOSED);
    }
}
