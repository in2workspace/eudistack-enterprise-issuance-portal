package es.in2.issuanceportal.journey.application;

import es.in2.issuanceportal.journey.api.JourneyAccess;
import es.in2.issuanceportal.journey.api.JourneyProgress;
import es.in2.issuanceportal.journey.api.JourneyWithdrawal;
import es.in2.issuanceportal.journey.application.port.JourneyAuditTrail;
import es.in2.issuanceportal.journey.application.port.JourneyRepository;
import es.in2.issuanceportal.journey.domain.Journey;
import es.in2.issuanceportal.journey.domain.JourneyClosedException;
import es.in2.issuanceportal.journey.domain.JourneyState;
import es.in2.issuanceportal.journey.domain.JourneyStep;
import es.in2.issuanceportal.journey.domain.WithdrawalReason;
import es.in2.issuanceportal.session.api.HolderAlreadyHasJourneyException;
import es.in2.issuanceportal.session.api.HolderIdentity;
import es.in2.issuanceportal.session.api.HolderRef;
import es.in2.issuanceportal.session.api.JourneySessions;
import es.in2.issuanceportal.session.api.Section;
import es.in2.issuanceportal.shared.config.TransactionConfiguration;
import es.in2.issuanceportal.shared.tenant.TenantId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Lifecycle of the holder's journey and its session: open, read, withdraw, and the checks the other
 * modules make through {@code journey.api}.
 *
 * <p>When a request finds its journey already closed, the clean-up it does on the way (withdrawing an
 * expired journey, deleting a session left behind) runs in its own transaction, so it commits even
 * though the request then fails with {@code journey_closed}, also when the caller is another module
 * with a transaction of its own.
 */
@Slf4j
@Service
@Transactional
public class JourneyService implements JourneyAccess, JourneyProgress, JourneyWithdrawal {

    /** Journeys the expiry sweep reads per query. */
    static final int SWEEP_BATCH = 200;
    /** Times an opening is tried when another one of the same holder gets there first. */
    private static final int OPEN_ATTEMPTS = 3;
    private static final UUID FIRST_ID = new UUID(0L, 0L);

    private final JourneyRepository journeys;
    private final JourneySessions sessions;
    private final JourneyAuditTrail trail;
    private final TransactionOperations transactions;
    private final TransactionOperations independent;
    private final Clock clock;

    public JourneyService(JourneyRepository journeys, JourneySessions sessions, JourneyAuditTrail trail,
                          TransactionOperations transactions,
                          @Qualifier(TransactionConfiguration.INDEPENDENT) TransactionOperations independent,
                          Clock clock) {
        this.journeys = journeys;
        this.sessions = sessions;
        this.trail = trail;
        this.transactions = transactions;
        this.independent = independent;
        this.clock = clock;
    }

    /** The holder's journey and whether this call opened it. */
    public record Opened(Journey journey, boolean created) {
    }

    /**
     * Opens the holder's journey with their identity, or returns the one they already have, without touching its
     * identity (design.md section 3.2). A journey whose inactivity limit has passed but the expiry job has not handled yet
     * is closed first, as the job would. Two openings at the same time end with a single journey: the session's
     * unique holder makes the second fail, and it is read again in a new transaction.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Opened open(TenantId tenant, HolderRef holder, HolderIdentity identity, String identificationMethod) {
        for (int attempt = 1; ; attempt++) {
            try {
                return Objects.requireNonNull(
                        transactions.execute(status -> openOnce(tenant, holder, identity, identificationMethod)));
            } catch (HolderAlreadyHasJourneyException | JourneyClosedException e) {
                // Another opening, or a request that closed the holder's journey, got there first: look again.
                if (attempt == OPEN_ATTEMPTS) {
                    throw e;
                }
                log.debug("The journey of the holder changed while it was being opened: looking again");
            }
        }
    }

    private Opened openOnce(TenantId tenant, HolderRef holder, HolderIdentity identity, String identificationMethod) {
        Instant now = clock.instant();
        Optional<Journey> existing = sessions.journeyOf(tenant, holder).flatMap(id -> journeys.findById(tenant, id));
        if (existing.isPresent()) {
            Journey journey = existing.get();
            if (journey.state() == JourneyState.WITHDRAWN) {
                sessions.close(tenant, journey.id());
            } else if (journey.isInactive(now)) {
                expireOnRequest(tenant, journey, now);
            } else {
                JourneyState readIn = journey.state();
                journey.touch(now);
                save(tenant, journey, readIn);
                return new Opened(journey, false);
            }
        }
        Journey journey = Journey.start(UUID.randomUUID(), now, identificationMethod);
        journeys.insert(tenant, journey);
        sessions.open(tenant, holder, journey.id());
        sessions.write(tenant, journey.id(), Section.IDENTITY, identity.toJson());
        audit(tenant, JourneyAuditTrail.Kind.STARTED, journey);
        return new Opened(journey, true);
    }

    public Journey current(TenantId tenant, HolderRef holder) {
        return active(tenant, holder);
    }

    /**
     * The holder cancels. In progress, the journey is withdrawn as cancelled; completed, only its
     * session closes. Either way the personal data is deleted.
     */
    public Journey cancel(TenantId tenant, HolderRef holder) {
        Journey journey = active(tenant, holder);
        if (journey.isInProgress()) {
            withdraw(tenant, journey, WithdrawalReason.CANCELLED);
        } else {
            sessions.close(tenant, journey.id());
        }
        return journey;
    }

    @Override
    public void requireActive(TenantId tenant, HolderRef holder) {
        active(tenant, holder);
    }

    @Override
    public void requireInProgress(TenantId tenant, HolderRef holder) {
        activeInProgress(tenant, holder);
    }

    @Override
    public void reach(TenantId tenant, HolderRef holder, JourneyProgress.Step step) {
        Journey journey = activeInProgress(tenant, holder);
        JourneyStep before = journey.step();
        journey.reach(switch (step) {
            case IDENTIFIED -> JourneyStep.IDENTIFIED;
            case ENTITLEMENT_CONFIRMED -> JourneyStep.ENTITLEMENT_CONFIRMED;
        }, clock.instant());
        save(tenant, journey, JourneyState.IN_PROGRESS);
        if (journey.step() != before) {
            audit(tenant, JourneyAuditTrail.Kind.STEP_REACHED, journey);
        }
    }

    @Override
    public void withdraw(TenantId tenant, HolderRef holder, JourneyWithdrawal.Cause cause) {
        Journey journey = activeInProgress(tenant, holder);
        withdraw(tenant, journey, switch (cause) {
            case NOT_ENTITLED -> WithdrawalReason.NOT_ENTITLED;
        });
    }

    /**
     * Expiry sweep of a tenant (design.md AD-4): every session inactive for 15 minutes is closed, and a
     * journey still in progress is withdrawn as expired. Only candidates are read, in pages, and each
     * journey is handled in its own transaction with writes that never overwrite a request that used it
     * in the meantime. Returns how many sessions were closed.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int expireInactive(TenantId tenant) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(Journey.INACTIVITY_LIMIT);
        int closed = 0;
        UUID after = FIRST_ID;
        List<UUID> batch;
        do {
            batch = journeys.findExpiryCandidates(tenant, cutoff, after, SWEEP_BATCH);
            for (UUID id : batch) {
                // A journey that cannot be handled must not keep the personal data of every other
                // session of the tenant past its expiry.
                try {
                    if (Boolean.TRUE.equals(transactions.execute(status -> expireOne(tenant, id, now, cutoff)))) {
                        closed++;
                    }
                } catch (RuntimeException e) {
                    log.error("Could not expire journey {}", id, e);
                }
                after = id;
            }
        } while (batch.size() == SWEEP_BATCH);
        return closed;
    }

    private boolean expireOne(TenantId tenant, UUID id, Instant now, Instant cutoff) {
        journeys.findById(tenant, id)
                .filter(journey -> journey.isInProgress() && journey.isInactive(now))
                .ifPresent(journey -> {
                    journey.withdraw(WithdrawalReason.EXPIRED, now);
                    if (journeys.updateIfInactive(tenant, journey, cutoff)) {
                        audit(tenant, JourneyAuditTrail.Kind.WITHDRAWN, journey);
                    }
                });
        return journeys.deleteSessionIfClosedOrInactive(tenant, id, cutoff);
    }

    /** The open journey of the holder, with its activity recorded, or {@code journey_closed}. */
    private Journey active(TenantId tenant, HolderRef holder) {
        Journey journey = sessions.journeyOf(tenant, holder)
                .flatMap(id -> journeys.findById(tenant, id))
                .orElseThrow(JourneyClosedException::new);
        Instant now = clock.instant();
        if (journey.state() == JourneyState.WITHDRAWN) {
            // A withdrawn journey has no session; clean up one left behind.
            independent.executeWithoutResult(status -> sessions.close(tenant, journey.id()));
            throw new JourneyClosedException();
        }
        if (journey.isInactive(now)) {
            independent.executeWithoutResult(status -> expireOnRequest(tenant, journey, now));
            throw new JourneyClosedException();
        }
        JourneyState readIn = journey.state();
        journey.touch(now);
        save(tenant, journey, readIn);
        return journey;
    }

    /** Same outcome as the sweep, for a request that arrives before it. */
    private void expireOnRequest(TenantId tenant, Journey journey, Instant now) {
        Instant cutoff = now.minus(Journey.INACTIVITY_LIMIT);
        if (journey.isInProgress()) {
            journey.withdraw(WithdrawalReason.EXPIRED, now);
            if (journeys.updateIfInactive(tenant, journey, cutoff)) {
                audit(tenant, JourneyAuditTrail.Kind.WITHDRAWN, journey);
            }
        }
        journeys.deleteSessionIfClosedOrInactive(tenant, journey.id(), cutoff);
    }

    private Journey activeInProgress(TenantId tenant, HolderRef holder) {
        Journey journey = active(tenant, holder);
        if (!journey.isInProgress()) {
            throw new JourneyClosedException();
        }
        return journey;
    }

    private void withdraw(TenantId tenant, Journey journey, WithdrawalReason reason) {
        journey.withdraw(reason, clock.instant());
        save(tenant, journey, JourneyState.IN_PROGRESS);
        sessions.close(tenant, journey.id());
        audit(tenant, JourneyAuditTrail.Kind.WITHDRAWN, journey);
    }

    /** The trail never decides the outcome of a request: a failure is logged and the journey goes on. */
    private void audit(TenantId tenant, JourneyAuditTrail.Kind kind, Journey journey) {
        try {
            trail.append(tenant, new JourneyAuditTrail.Event(UUID.randomUUID(), kind, journey.id(), journey.state(),
                    journey.step(), journey.withdrawalReason(), clock.instant()));
        } catch (RuntimeException e) {
            log.error("Could not record the {} event of journey {}", kind, journey.id(), e);
        }
    }

    /** Someone else changed the journey first (another request or the expiry job): it is closed now. */
    private void save(TenantId tenant, Journey journey, JourneyState readIn) {
        if (!journeys.update(tenant, journey, readIn)) {
            throw new JourneyClosedException();
        }
    }
}
