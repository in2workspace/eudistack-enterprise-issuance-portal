package es.in2.issuanceportal.journey.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The holder's journey (prd.md §3.2). It holds no personal data and is never deleted: leaving
 * {@code in_progress} only fixes its end, its last step and, when withdrawn, the reason.
 */
public final class Journey {

    /** A journey in progress without activity for this long is withdrawn as expired (design.md AD-4). */
    public static final Duration INACTIVITY_LIMIT = Duration.ofMinutes(15);

    private final UUID id;
    private final Instant createdAt;
    private final String identificationMethod;
    private UUID issuanceProcessId;
    private JourneyState state;
    private JourneyStep step;
    private WithdrawalReason withdrawalReason;
    private Instant lastActivity;
    private Instant endedAt;

    @SuppressWarnings("java:S107") // Mirrors the persisted record one to one.
    private Journey(UUID id, JourneyState state, JourneyStep step, WithdrawalReason withdrawalReason,
                    String identificationMethod, UUID issuanceProcessId,
                    Instant createdAt, Instant lastActivity, Instant endedAt) {
        this.id = id;
        this.state = state;
        this.step = step;
        this.withdrawalReason = withdrawalReason;
        this.identificationMethod = identificationMethod;
        this.issuanceProcessId = issuanceProcessId;
        this.createdAt = createdAt;
        this.lastActivity = lastActivity;
        this.endedAt = endedAt;
    }

    public static Journey start(UUID id, Instant now) {
        return new Journey(id, JourneyState.IN_PROGRESS, JourneyStep.STARTED, null, null, null, now, now, null);
    }

    /** Rebuilds a journey from storage. */
    @SuppressWarnings("java:S107")
    public static Journey restore(UUID id, JourneyState state, JourneyStep step, WithdrawalReason withdrawalReason,
                                  String identificationMethod, UUID issuanceProcessId,
                                  Instant createdAt, Instant lastActivity, Instant endedAt) {
        return new Journey(id, state, step, withdrawalReason, identificationMethod, issuanceProcessId,
                createdAt, lastActivity, endedAt);
    }

    /**
     * Records activity, which pushes back the expiry. A completed journey keeps its session, to ask for
     * a new offer, until it is closed or goes inactive (prd.md §3.2), so its activity counts too.
     */
    public void touch(Instant now) {
        if (state == JourneyState.WITHDRAWN) {
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

    /**
     * Records the issuance process created for this journey (design.md AD-12). A journey has a single
     * process: recording the same one again changes nothing, a different one is rejected.
     */
    public void recordIssuanceProcess(UUID processId, Instant now) {
        requireInProgress();
        if (issuanceProcessId != null && !issuanceProcessId.equals(processId)) {
            throw new IssuanceProcessAlreadyRecordedException();
        }
        issuanceProcessId = processId;
        lastActivity = now;
    }

    public void withdraw(WithdrawalReason reason, Instant now) {
        requireInProgress();
        state = JourneyState.WITHDRAWN;
        withdrawalReason = reason;
        endedAt = now;
    }

    public void complete(Instant now) {
        requireInProgress();
        state = JourneyState.COMPLETED;
        step = JourneyStep.OFFER_OBTAINED;
        lastActivity = now;
        endedAt = now;
    }

    /**
     * True once the inactivity limit has passed for a journey that still has a session, even if the
     * expiry job has not handled it yet. In progress, it is withdrawn as expired; completed, only its
     * session closes.
     */
    public boolean isInactive(Instant now) {
        return state != JourneyState.WITHDRAWN && !now.isBefore(lastActivity.plus(INACTIVITY_LIMIT));
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

    public Optional<UUID> issuanceProcessId() {
        return Optional.ofNullable(issuanceProcessId);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant lastActivity() {
        return lastActivity;
    }

    public Optional<Instant> endedAt() {
        return Optional.ofNullable(endedAt);
    }
}
