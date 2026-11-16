package es.in2.issuanceportal.journey.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The holder's journey (prd.md §3.2). It holds no personal data and is never deleted: it is withdrawn before the
 * first offer, or completed with it, and a journey that is withdrawn, ended or expired fixes its end.
 */
public final class Journey {

    /** A journey in progress without activity for this long is withdrawn as expired (design.md AD-4). */
    public static final Duration INACTIVITY_LIMIT = Duration.ofMinutes(15);

    private final UUID id;
    private final Instant createdAt;
    private final String identificationMethod;
    private JourneyState state;
    private JourneyStep step;
    private WithdrawalReason withdrawalReason;
    private Instant lastActivity;
    private Instant completedAt;
    private Instant endedAt;

    @SuppressWarnings("java:S107") // Mirrors the persisted record one to one.
    private Journey(UUID id, JourneyState state, JourneyStep step, WithdrawalReason withdrawalReason,
                    String identificationMethod, Instant createdAt, Instant completedAt,
                    Instant lastActivity, Instant endedAt) {
        this.id = id;
        this.state = state;
        this.step = step;
        this.withdrawalReason = withdrawalReason;
        this.identificationMethod = identificationMethod;
        this.createdAt = createdAt;
        this.completedAt = completedAt;
        this.lastActivity = lastActivity;
        this.endedAt = endedAt;
    }

    /**
     * A journey opened for a holder the Access Token identifies: it starts at {@code identified}, the first step of
     * the contract (design.md §3.2). {@code started} is only read from journeys opened before EUD-314.
     *
     * @param identificationMethod how the holder logged in; {@code null} when it is not known
     */
    public static Journey start(UUID id, Instant now, String identificationMethod) {
        return new Journey(id, JourneyState.IN_PROGRESS, JourneyStep.IDENTIFIED, null, identificationMethod,
                now, null, now, null);
    }

    /** Rebuilds a journey from storage. */
    @SuppressWarnings("java:S107")
    public static Journey restore(UUID id, JourneyState state, JourneyStep step, WithdrawalReason withdrawalReason,
                                  String identificationMethod, Instant createdAt, Instant completedAt,
                                  Instant lastActivity, Instant endedAt) {
        return new Journey(id, state, step, withdrawalReason, identificationMethod, createdAt, completedAt,
                lastActivity, endedAt);
    }

    /**
     * Records activity, which pushes back the expiry. A completed journey stays active, for the other requests and
     * new offers, until it ends or goes inactive (design.md AD-18), so its activity counts too.
     */
    public void touch(Instant now) {
        if (endedAt != null) {
            throw new JourneyClosedException();
        }
        lastActivity = now;
    }

    /** Moves to a later step. Reaching a step already passed changes nothing. */
    public void reach(JourneyStep reached, Instant now) {
        requireInProgress();
        if (reached.compareTo(step) > 0) {
            step = reached;
        }
        lastActivity = now;
    }

    public void withdraw(WithdrawalReason reason, Instant now) {
        requireInProgress();
        state = JourneyState.WITHDRAWN;
        withdrawalReason = reason;
        endedAt = now;
    }

    /** The first offer was delivered: the journey is completed and stays active, with no end (design.md AD-18). */
    public void complete(Instant now) {
        requireInProgress();
        state = JourneyState.COMPLETED;
        step = JourneyStep.OFFER_OBTAINED;
        completedAt = now;
        lastActivity = now;
    }

    /** The holder ends a completed journey, or it goes inactive: it stays completed and is no longer active. */
    public void end(Instant now) {
        if (state != JourneyState.COMPLETED || endedAt != null) {
            throw new JourneyClosedException();
        }
        endedAt = now;
    }

    /**
     * True once the inactivity limit has passed for a journey that is still active, even if the expiry job has
     * not handled it yet. In progress, it is withdrawn as expired; completed, it ends.
     */
    public boolean isInactive(Instant now) {
        return endedAt == null && !now.isBefore(lastActivity.plus(INACTIVITY_LIMIT));
    }

    /** Active until it ends: in progress, or completed and not ended (design.md AD-18). */
    public boolean isActive() {
        return endedAt == null;
    }

    public boolean isCompleted() {
        return state == JourneyState.COMPLETED;
    }

    public boolean isInProgress() {
        return state == JourneyState.IN_PROGRESS;
    }

    private void requireInProgress() {
        if (state != JourneyState.IN_PROGRESS) {
            throw new JourneyClosedException();
        }
    }

    public UUID id() {
        return id;
    }

    public JourneyState state() {
        return state;
    }

    public JourneyStep step() {
        return step;
    }

    public Optional<WithdrawalReason> withdrawalReason() {
        return Optional.ofNullable(withdrawalReason);
    }

    public Optional<String> identificationMethod() {
        return Optional.ofNullable(identificationMethod);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant lastActivity() {
        return lastActivity;
    }

    public Optional<Instant> completedAt() {
        return Optional.ofNullable(completedAt);
    }

    public Optional<Instant> endedAt() {
        return Optional.ofNullable(endedAt);
    }
}
