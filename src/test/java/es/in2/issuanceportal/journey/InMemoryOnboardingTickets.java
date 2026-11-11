package es.in2.issuanceportal.journey;

import es.in2.issuanceportal.journey.application.port.OnboardingTicket;
import es.in2.issuanceportal.journey.application.port.OnboardingTicketException;
import es.in2.issuanceportal.journey.application.port.OnboardingTickets;
import es.in2.issuanceportal.shared.tenant.TenantId;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * An {@link OnboardingTickets} for the tests of the use cases that ask for a ticket. Each call gets a new
 * ticket that lasts what was asked, unless the service was told to fail. Safe to call from several threads.
 */
public final class InMemoryOnboardingTickets implements OnboardingTickets {

    /** One call to {@link #request}. */
    public record Call(TenantId tenant, String walletUnitServiceUrl, String email, String credentialOffer,
                       Duration expiresIn) {
    }

    private final Clock clock;
    private final List<Call> calls = new ArrayList<>();
    private OnboardingTicketException.Reason failure;

    public InMemoryOnboardingTickets(Clock clock) {
        this.clock = clock;
    }

    /** From now on, {@link #request} fails with this reason. */
    public synchronized void failWith(OnboardingTicketException.Reason reason) {
        this.failure = reason;
    }

    /** Forgets the calls and any failure, for a test that shares the service with others. */
    public synchronized void reset() {
        calls.clear();
        failure = null;
    }

    /** From now on, {@link #request} answers again. */
    public synchronized void recover() {
        this.failure = null;
    }

    @Override
    public synchronized OnboardingTicket request(TenantId tenant, String walletUnitServiceUrl, String email,
                                                 String credentialOffer, Duration expiresIn) {
        calls.add(new Call(tenant, walletUnitServiceUrl, email, credentialOffer, expiresIn));
        if (failure != null) {
            throw new OnboardingTicketException(failure);
        }
        return new OnboardingTicket("https://" + tenant.value() + ".wallet.test/t/ticket-" + calls.size(),
                clock.instant().plus(expiresIn));
    }

    public synchronized List<Call> calls() {
        return List.copyOf(calls);
    }
}
