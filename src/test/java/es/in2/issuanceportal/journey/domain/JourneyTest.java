package es.in2.issuanceportal.journey.domain;

import es.in2.issuanceportal.shared.error.ProblemCode;
import es.in2.issuanceportal.shared.error.ProblemException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JourneyTest {

    private static final Instant T0 = Instant.parse("2026-10-19T10:00:00Z");

    @Test
    void startsInProgressAtTheFirstStepWithoutEnd() {
        Journey journey = Journey.start(UUID.randomUUID(), T0);

        assertThat(journey.state()).isEqualTo(JourneyState.IN_PROGRESS);
        assertThat(journey.step()).isEqualTo(JourneyStep.STARTED);
        assertThat(journey.withdrawalReason()).isEmpty();
        assertThat(journey.endedAt()).isEmpty();
        assertThat(journey.createdAt()).isEqualTo(T0);
        assertThat(journey.lastActivity()).isEqualTo(T0);
    }

    @Test
    void withdrawingKeepsTheStepReachedAndRecordsReasonAndEnd() {
        Journey journey = Journey.start(UUID.randomUUID(), T0);
        journey.reach(JourneyStep.ENTITLEMENT_CONFIRMED, T0.plusSeconds(60));

        journey.withdraw(WithdrawalReason.CANCELLED, T0.plusSeconds(90));

        assertThat(journey.state()).isEqualTo(JourneyState.WITHDRAWN);
        assertThat(journey.step()).isEqualTo(JourneyStep.ENTITLEMENT_CONFIRMED);
        assertThat(journey.withdrawalReason()).contains(WithdrawalReason.CANCELLED);
        assertThat(journey.endedAt()).contains(T0.plusSeconds(90));
    }

    @Test
    void stepsOnlyMoveForward() {
        Journey journey = Journey.start(UUID.randomUUID(), T0);
        journey.reach(JourneyStep.ENTITLEMENT_CONFIRMED, T0);

        journey.reach(JourneyStep.IDENTIFIED, T0.plusSeconds(10));

        assertThat(journey.step()).isEqualTo(JourneyStep.ENTITLEMENT_CONFIRMED);
        assertThat(journey.lastActivity()).isEqualTo(T0.plusSeconds(10));
    }

    @Test
    void completingReachesTheOfferAndEndsTheJourney() {
        Journey journey = Journey.start(UUID.randomUUID(), T0);

        journey.complete(T0.plusSeconds(120));

        assertThat(journey.state()).isEqualTo(JourneyState.COMPLETED);
        assertThat(journey.step()).isEqualTo(JourneyStep.OFFER_OBTAINED);
        assertThat(journey.withdrawalReason()).isEmpty();
        assertThat(journey.endedAt()).contains(T0.plusSeconds(120));
    }

    @Test
    void expiresAfterFifteenMinutesWithoutActivity() {
        Journey journey = Journey.start(UUID.randomUUID(), T0);

        assertThat(journey.isExpired(T0.plus(Duration.ofMinutes(15)).minusMillis(1))).isFalse();
        assertThat(journey.isExpired(T0.plus(Duration.ofMinutes(15)))).isTrue();
    }

    @Test
    void activityPushesBackTheExpiry() {
        Journey journey = Journey.start(UUID.randomUUID(), T0);

        journey.touch(T0.plus(Duration.ofMinutes(14)));

        assertThat(journey.isExpired(T0.plus(Duration.ofMinutes(20)))).isFalse();
        assertThat(journey.isExpired(T0.plus(Duration.ofMinutes(29)))).isTrue();
    }

    @Test
    void aClosedJourneyNeverExpires() {
        Journey journey = Journey.start(UUID.randomUUID(), T0);
        journey.withdraw(WithdrawalReason.CANCELLED, T0);

        assertThat(journey.isExpired(T0.plus(Duration.ofHours(1)))).isFalse();
    }

    @Test
    void aWithdrawnJourneyCannotChangeAnymore() {
        Journey journey = Journey.start(UUID.randomUUID(), T0);
        journey.withdraw(WithdrawalReason.EXPIRED, T0);

        assertJourneyClosed(() -> journey.withdraw(WithdrawalReason.CANCELLED, T0));
        assertJourneyClosed(() -> journey.complete(T0));
        assertJourneyClosed(() -> journey.reach(JourneyStep.IDENTIFIED, T0));
        assertJourneyClosed(() -> journey.touch(T0));
    }

    @Test
    void aCompletedJourneyCannotBeWithdrawn() {
        Journey journey = Journey.start(UUID.randomUUID(), T0);
        journey.complete(T0);

        assertJourneyClosed(() -> journey.withdraw(WithdrawalReason.CANCELLED, T0));
    }

    @Test
    void codesMatchTheContract() {
        assertThat(JourneyState.fromCode("in_progress")).isEqualTo(JourneyState.IN_PROGRESS);
        assertThat(JourneyStep.fromCode("entitlement_confirmed")).isEqualTo(JourneyStep.ENTITLEMENT_CONFIRMED);
        assertThat(WithdrawalReason.fromCode("not_entitled")).isEqualTo(WithdrawalReason.NOT_ENTITLED);
    }

    private static void assertJourneyClosed(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(ProblemException.class)
                .extracting(e -> ((ProblemException) e).problem())
                .isEqualTo(ProblemCode.JOURNEY_CLOSED);
    }
}
