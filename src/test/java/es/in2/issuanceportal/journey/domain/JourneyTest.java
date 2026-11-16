package es.in2.issuanceportal.journey.domain;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JourneyTest {

    private static final Instant T0 = Instant.parse("2026-10-19T10:00:00Z");

    @Test
    void start_newJourney_isInProgressAtFirstStepWithoutEnd() {
        Journey journey = Journey.start(UUID.randomUUID(), T0, null);

        assertThat(journey.state()).isEqualTo(JourneyState.IN_PROGRESS);
        assertThat(journey.step()).isEqualTo(JourneyStep.IDENTIFIED);
        assertThat(journey.withdrawalReason()).isEmpty();
        assertThat(journey.endedAt()).isEmpty();
        assertThat(journey.createdAt()).isEqualTo(T0);
        assertThat(journey.lastActivity()).isEqualTo(T0);
    }

    @Test
    void withdraw_inProgress_keepsStepAndRecordsReasonAndEnd() {
        Journey journey = Journey.start(UUID.randomUUID(), T0, null);
        journey.reach(JourneyStep.ENTITLEMENT_CONFIRMED, T0.plusSeconds(60));

        journey.withdraw(WithdrawalReason.CANCELLED, T0.plusSeconds(90));

        assertThat(journey.state()).isEqualTo(JourneyState.WITHDRAWN);
        assertThat(journey.step()).isEqualTo(JourneyStep.ENTITLEMENT_CONFIRMED);
        assertThat(journey.withdrawalReason()).contains(WithdrawalReason.CANCELLED);
        assertThat(journey.endedAt()).contains(T0.plusSeconds(90));
    }

    @Test
    void reach_earlierStep_keepsLaterStep() {
        Journey journey = Journey.start(UUID.randomUUID(), T0, null);
        journey.reach(JourneyStep.ENTITLEMENT_CONFIRMED, T0);

        journey.reach(JourneyStep.IDENTIFIED, T0.plusSeconds(10));

        assertThat(journey.step()).isEqualTo(JourneyStep.ENTITLEMENT_CONFIRMED);
        assertThat(journey.lastActivity()).isEqualTo(T0.plusSeconds(10));
    }

    @Test
    void complete_inProgress_reachesOfferWithoutEnding() {
        Journey journey = Journey.start(UUID.randomUUID(), T0, null);

        journey.complete(T0.plusSeconds(120));

        assertThat(journey.state()).isEqualTo(JourneyState.COMPLETED);
        assertThat(journey.step()).isEqualTo(JourneyStep.OFFER_OBTAINED);
        assertThat(journey.withdrawalReason()).isEmpty();
        // AD-18: the first offer completes the journey, which stays active: it has no end yet.
        assertThat(journey.endedAt()).isEmpty();
        assertThat(journey.completedAt()).contains(T0.plusSeconds(120));
    }

    @Test
    void isInactive_fifteenMinutesWithoutActivity_returnsTrue() {
        Journey journey = Journey.start(UUID.randomUUID(), T0, null);

        assertThat(journey.isInactive(T0.plus(Duration.ofMinutes(15)).minusMillis(1))).isFalse();
        assertThat(journey.isInactive(T0.plus(Duration.ofMinutes(15)))).isTrue();
    }

    @Test
    void touch_beforeLimit_pushesBackExpiry() {
        Journey journey = Journey.start(UUID.randomUUID(), T0, null);

        journey.touch(T0.plus(Duration.ofMinutes(14)));

        assertThat(journey.isInactive(T0.plus(Duration.ofMinutes(20)))).isFalse();
        assertThat(journey.isInactive(T0.plus(Duration.ofMinutes(29)))).isTrue();
    }

    @Test
    void touch_completedJourney_keepsCountingActivity() {
        Journey journey = Journey.start(UUID.randomUUID(), T0, null);
        journey.complete(T0);

        journey.touch(T0.plus(Duration.ofMinutes(10)));

        assertThat(journey.isInactive(T0.plus(Duration.ofMinutes(20)))).isFalse();
        assertThat(journey.isInactive(T0.plus(Duration.ofMinutes(25)))).isTrue();
    }

    @Test
    void isInactive_withdrawnJourney_returnsFalse() {
        Journey journey = Journey.start(UUID.randomUUID(), T0, null);
        journey.withdraw(WithdrawalReason.CANCELLED, T0);

        assertThat(journey.isInactive(T0.plus(Duration.ofHours(1)))).isFalse();
    }

    @Test
    void transitions_withdrawnJourney_throwJourneyClosed() {
        Journey journey = Journey.start(UUID.randomUUID(), T0, null);
        journey.withdraw(WithdrawalReason.EXPIRED, T0);

        assertJourneyClosed(() -> journey.withdraw(WithdrawalReason.CANCELLED, T0));
        assertJourneyClosed(() -> journey.complete(T0));
        assertJourneyClosed(() -> journey.reach(JourneyStep.IDENTIFIED, T0));
        assertJourneyClosed(() -> journey.touch(T0));
    }

    @Test
    void withdraw_completedJourney_throwsJourneyClosed() {
        Journey journey = Journey.start(UUID.randomUUID(), T0, null);
        journey.complete(T0);

        assertJourneyClosed(() -> journey.withdraw(WithdrawalReason.CANCELLED, T0));
    }

    @Test
    void fromCode_contractCodes_returnMatchingValues() {
        assertThat(JourneyState.fromCode("in_progress")).isEqualTo(JourneyState.IN_PROGRESS);
        assertThat(JourneyStep.fromCode("entitlement_confirmed")).isEqualTo(JourneyStep.ENTITLEMENT_CONFIRMED);
        assertThat(WithdrawalReason.fromCode("not_entitled")).isEqualTo(WithdrawalReason.NOT_ENTITLED);
    }

    @Test
    @DisplayName("AC-25 — complete_inProgress_isCompletedWithItsMomentAndStaysActive")
    void complete_inProgress_isCompletedWithItsMomentAndStaysActive() {
        Journey journey = Journey.start(UUID.randomUUID(), T0, null);

        journey.complete(T0.plusSeconds(30));

        assertThat(journey.state()).isEqualTo(JourneyState.COMPLETED);
        assertThat(journey.step()).isEqualTo(JourneyStep.OFFER_OBTAINED);
        assertThat(journey.completedAt()).contains(T0.plusSeconds(30));
        assertThat(journey.endedAt()).isEmpty();
        assertThat(journey.isActive()).isTrue();
        assertThat(journey.lastActivity()).isEqualTo(T0.plusSeconds(30));
    }

    @Test
    @DisplayName("AC-25 — touch_completedAndActive_countsAsActivity")
    void touch_completedAndActive_countsAsActivity() {
        Journey journey = Journey.start(UUID.randomUUID(), T0, null);
        journey.complete(T0);

        journey.touch(T0.plusSeconds(60));

        assertThat(journey.lastActivity()).isEqualTo(T0.plusSeconds(60));
    }

    @Test
    @DisplayName("AC-25 — end_completed_staysCompletedWithItsEndAndIsNoLongerActive")
    void end_completed_staysCompletedWithItsEndAndIsNoLongerActive() {
        Journey journey = Journey.start(UUID.randomUUID(), T0, null);
        journey.complete(T0);

        journey.end(T0.plusSeconds(90));

        assertThat(journey.state()).isEqualTo(JourneyState.COMPLETED);
        assertThat(journey.endedAt()).contains(T0.plusSeconds(90));
        assertThat(journey.completedAt()).contains(T0);
        assertThat(journey.isActive()).isFalse();
        assertJourneyClosed(() -> journey.touch(T0.plusSeconds(100)));
        assertJourneyClosed(() -> journey.end(T0.plusSeconds(100)));
    }

    @Test
    void end_inProgress_throwsJourneyClosed() {
        Journey journey = Journey.start(UUID.randomUUID(), T0, null);

        assertJourneyClosed(() -> journey.end(T0));
    }

    @Test
    @DisplayName("AC-25 — isInactive_completedAfterTheLimit_endsLikeAJourneyInProgressExpires")
    void isInactive_completedAfterTheLimit_endsLikeAJourneyInProgressExpires() {
        Journey journey = Journey.start(UUID.randomUUID(), T0, null);
        journey.complete(T0);

        assertThat(journey.isInactive(T0.plus(Journey.INACTIVITY_LIMIT).minusSeconds(1))).isFalse();
        assertThat(journey.isInactive(T0.plus(Journey.INACTIVITY_LIMIT))).isTrue();
    }

    @Test
    void isInactive_endedJourney_isNotAskedAgain() {
        Journey journey = Journey.start(UUID.randomUUID(), T0, null);
        journey.complete(T0);
        journey.end(T0.plusSeconds(1));

        assertThat(journey.isInactive(T0.plus(Journey.INACTIVITY_LIMIT).plusSeconds(1))).isFalse();
    }

    @Test
    void complete_closedJourney_throwsJourneyClosed() {
        Journey withdrawn = Journey.start(UUID.randomUUID(), T0, null);
        withdrawn.withdraw(WithdrawalReason.CANCELLED, T0);
        Journey completed = Journey.start(UUID.randomUUID(), T0, null);
        completed.complete(T0);

        assertJourneyClosed(() -> withdrawn.complete(T0));
        assertJourneyClosed(() -> completed.complete(T0));
    }

    private static void assertJourneyClosed(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(JourneyClosedException.class);
    }
}
