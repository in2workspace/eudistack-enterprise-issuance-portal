package es.in2.issuanceportal.journey.application;

import es.in2.issuanceportal.journey.api.JourneyAccess;
import es.in2.issuanceportal.journey.api.JourneyProgress;
import es.in2.issuanceportal.journey.api.JourneyWithdrawal;
import es.in2.issuanceportal.journey.application.port.JourneyAuditTrail;
import es.in2.issuanceportal.journey.application.port.JourneyRepository;
import es.in2.issuanceportal.journey.domain.Journey;
import es.in2.issuanceportal.journey.domain.JourneyState;
import es.in2.issuanceportal.journey.domain.JourneyStep;
import es.in2.issuanceportal.journey.domain.WithdrawalReason;
import es.in2.issuanceportal.session.api.JourneySessions;
import es.in2.issuanceportal.session.api.SessionId;
import es.in2.issuanceportal.shared.error.ProblemCode;
import es.in2.issuanceportal.shared.error.ProblemException;
import es.in2.issuanceportal.shared.tenant.TenantId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Lifecycle of the holder's journey and its session: start, read, withdraw, and the checks the other
 * modules make through {@code journey.api}.
 *
 * <p>Transactions do not roll back on {@link ProblemException}: when an inactive journey is found,
 * its expiry is saved and then the request is answered {@code journey_closed}.
 */
@Slf4j
@Service
@Transactional(noRollbackFor = ProblemException.class)
public class JourneyService implements JourneyAccess, JourneyProgress, JourneyWithdrawal {

    private final JourneyRepository journeys;
    private final JourneySessions sessions;
    private final JourneyAuditTrail trail;
    private final TransactionOperations transactions;
    private final Clock clock;

    public JourneyService(JourneyRepository journeys, JourneySessions sessions, JourneyAuditTrail trail,
                          TransactionOperations transactions, Clock clock) {
        this.journeys = journeys;
        this.sessions = sessions;
        this.trail = trail;
        this.transactions = transactions;
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
     * journey still in progress is withdrawn as expired. A journey a request changed in the meantime is
     * left to that request. Returns how many sessions were closed.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int expireInactive(TenantId tenant) {
        Instant now = clock.instant();
        int closed = 0;
        // One transaction per journey: a journey that cannot be handled must not keep the
        // personal data of every other session of the tenant past its expiry.
        for (UUID id : sessions.openJourneys(tenant)) {
            try {
                if (Boolean.TRUE.equals(transactions.execute(status -> expireOne(tenant, id, now)))) {
                    closed++;
                }
            } catch (RuntimeException e) {
                log.error("Could not expire journey {}", id, e);
            }
        }
        return closed;
    }

    private boolean expireOne(TenantId tenant, UUID id, Instant now) {
        if (journeys.findById(tenant, id).map(journey -> expireIfInactive(tenant, journey, now)).orElse(true)) {
            sessions.close(tenant, id);
            return true;
        }
        return false;
    }

    /** Whether the journey's session must close: inactive, or left behind by a withdrawn journey. */
    private boolean expireIfInactive(TenantId tenant, Journey journey, Instant now) {
        if (journey.state() == JourneyState.WITHDRAWN) {
            return true;
        }
        if (!journey.isInactive(now)) {
            return false;
        }
        if (journey.isInProgress()) {
            journey.withdraw(WithdrawalReason.EXPIRED, now);
            if (!journeys.update(tenant, journey, JourneyState.IN_PROGRESS)) {
                return false;
            }
            audit(tenant, JourneyAuditTrail.Kind.WITHDRAWN, journey);
        }
        return true;
    }

    /** The open journey of the session, with its activity recorded, or {@code journey_closed}. */
    private Journey active(TenantId tenant, SessionId session) {
        Journey journey = sessions.journeyOf(tenant, session)
                .flatMap(id -> journeys.findById(tenant, id))
                .orElseThrow(JourneyService::closed);
        Instant now = clock.instant();
        if (journey.state() == JourneyState.WITHDRAWN) {
            // A withdrawn journey has no session; clean up one left behind.
            sessions.close(tenant, journey.id());
            throw closed();
        }
        JourneyState readIn = journey.state();
        if (journey.isInactive(now)) {
            if (journey.isInProgress()) {
                journey.withdraw(WithdrawalReason.EXPIRED, now);
                save(tenant, journey, readIn);
                audit(tenant, JourneyAuditTrail.Kind.WITHDRAWN, journey);
            }
            sessions.close(tenant, journey.id());
            throw closed();
        }
        journey.touch(now);
        save(tenant, journey, readIn);
        return journey;
    }

    private Journey activeInProgress(TenantId tenant, SessionId session) {
        Journey journey = active(tenant, session);
        if (!journey.isInProgress()) {
            throw closed();
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
                    try {
                        withdraw(tenant, journey, WithdrawalReason.CANCELLED);
                    } catch (ProblemException e) {
                        // A parallel request (a double submit) or the expiry job closed it first: it is
                        // closed either way, and the holder still gets a new journey.
                        if (e.problem() != ProblemCode.JOURNEY_CLOSED) {
                            throw e;
                        }
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
            trail.record(tenant, new JourneyAuditTrail.Event(UUID.randomUUID(), kind, journey.id(), journey.state(),
                    journey.step(), journey.withdrawalReason(), clock.instant()));
        } catch (RuntimeException e) {
            log.error("Could not record the {} event of journey {}", kind, journey.id(), e);
        }
    }

    /** Someone else changed the journey first (another request or the expiry job): it is closed now. */
    private void save(TenantId tenant, Journey journey, JourneyState readIn) {
        if (!journeys.update(tenant, journey, readIn)) {
            throw closed();
        }
    }

    private static ProblemException closed() {
        return new ProblemException(ProblemCode.JOURNEY_CLOSED);
    }
}
