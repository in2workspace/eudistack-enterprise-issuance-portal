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
import es.in2.issuanceportal.session.api.JourneySessions;
import es.in2.issuanceportal.session.api.SessionId;
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
import java.util.Optional;
import java.util.UUID;

/**
 * Lifecycle of the holder's journey and its session: start, read, withdraw, and the checks the other
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

    public record Started(Journey journey, SessionId session) {
    }

    /** Starts a journey with a new session. A journey still open in the same browser is closed first. */
    public Started start(TenantId tenant, Optional<SessionId> previous) {
        previous.ifPresent(session -> closePrevious(tenant, session));
        Instant now = clock.instant();
        Journey journey = Journey.start(UUID.randomUUID(), now);
        SessionId session = SessionId.generate();
        journeys.insert(tenant, journey);
        sessions.open(tenant, session, journey.id());
        audit(tenant, JourneyAuditTrail.Kind.STARTED, journey);
        return new Started(journey, session);
    }

    public Journey current(TenantId tenant, SessionId session) {
        return active(tenant, session);
    }

    /**
     * The holder cancels. In progress, the journey is withdrawn as cancelled; completed, only its
     * session closes. Either way the personal data is deleted.
     */
    public Journey cancel(TenantId tenant, SessionId session) {
        Journey journey = active(tenant, session);
        if (journey.isInProgress()) {
            withdraw(tenant, journey, WithdrawalReason.CANCELLED);
        } else {
            sessions.close(tenant, journey.id());
        }
        return journey;
    }

    @Override
    public void requireActive(TenantId tenant, SessionId session) {
        active(tenant, session);
    }

    @Override
    public void reach(TenantId tenant, SessionId session, JourneyProgress.Step step) {
        Journey journey = activeInProgress(tenant, session);
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
    public void withdraw(TenantId tenant, SessionId session, JourneyWithdrawal.Cause cause) {
        Journey journey = activeInProgress(tenant, session);
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

    /** The open journey of the session, with its activity recorded, or {@code journey_closed}. */
    private Journey active(TenantId tenant, SessionId session) {
        Journey journey = sessions.journeyOf(tenant, session)
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

    private Journey activeInProgress(TenantId tenant, SessionId session) {
        Journey journey = active(tenant, session);
        if (!journey.isInProgress()) {
            throw new JourneyClosedException();
        }
        return journey;
    }

    private void closePrevious(TenantId tenant, SessionId session) {
        sessions.journeyOf(tenant, session)
                .flatMap(id -> journeys.findById(tenant, id))
                .ifPresent(journey -> {
                    if (!journey.isInProgress()) {
                        sessions.close(tenant, journey.id());
                        return;
                    }
                    // A previous journey already past its inactivity limit expired; it was not cancelled.
                    Instant now = clock.instant();
                    if (journey.isInactive(now)) {
                        expireOnRequest(tenant, journey, now);
                        return;
                    }
                    try {
                        withdraw(tenant, journey, WithdrawalReason.CANCELLED);
                    } catch (JourneyClosedException _) {
                        // A parallel request (a double submit) or the expiry job closed it first: it is
                        // closed either way, and the holder still gets a new journey.
                        log.debug("Previous journey {} was already closed", journey.id());
                    }
                });
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
