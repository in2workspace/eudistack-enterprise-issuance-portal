package es.in2.issuanceportal.journey.application;

import es.in2.issuanceportal.journey.api.JourneyAccess;
import es.in2.issuanceportal.journey.api.JourneyProgress;
import es.in2.issuanceportal.journey.api.JourneyWithdrawal;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
@Service
@Transactional(noRollbackFor = ProblemException.class)
public class JourneyService implements JourneyAccess, JourneyProgress, JourneyWithdrawal {

    private final JourneyRepository journeys;
    private final JourneySessions sessions;
    private final Clock clock;

    public JourneyService(JourneyRepository journeys, JourneySessions sessions, Clock clock) {
        this.journeys = journeys;
        this.sessions = sessions;
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
        journey.reach(switch (step) {
            case IDENTIFIED -> JourneyStep.IDENTIFIED;
            case ENTITLEMENT_CONFIRMED -> JourneyStep.ENTITLEMENT_CONFIRMED;
        }, clock.instant());
        save(tenant, journey, JourneyState.IN_PROGRESS);
    }

    @Override
    public void withdraw(TenantId tenant, SessionId session, JourneyWithdrawal.Cause cause) {
        Journey journey = activeInProgress(tenant, session);
        withdraw(tenant, journey, switch (cause) {
            case NOT_ENTITLED -> WithdrawalReason.NOT_ENTITLED;
        });
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
                    if (journey.isInProgress()) {
                        withdraw(tenant, journey, WithdrawalReason.CANCELLED);
                    } else {
                        sessions.close(tenant, journey.id());
                    }
                });
    }

    private void withdraw(TenantId tenant, Journey journey, WithdrawalReason reason) {
        journey.withdraw(reason, clock.instant());
        save(tenant, journey, JourneyState.IN_PROGRESS);
        sessions.close(tenant, journey.id());
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
