package es.in2.issuanceportal.journey;

import es.in2.issuanceportal.journey.application.port.SystemToken;
import es.in2.issuanceportal.journey.application.port.SystemTokenProvider;
import es.in2.issuanceportal.journey.application.port.SystemTokenUnavailableException;
import es.in2.issuanceportal.shared.tenant.TenantId;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link SystemTokenProvider} for the tests of the use cases that call the Issuer or the Wallet Unit Service.
 * Every call gets a new token ("token-N") unless the provider was told to fail.
 */
public final class InMemorySystemTokenProvider implements SystemTokenProvider {

    private final Clock clock;
    private final List<TenantId> requests = new ArrayList<>();
    private final List<TenantId> invalidations = new ArrayList<>();
    private SystemTokenUnavailableException.Reason failure;

    public InMemorySystemTokenProvider(Clock clock) {
        this.clock = clock;
    }

    /** From now on, {@link #tokenFor} fails with this reason. */
    public void failWith(SystemTokenUnavailableException.Reason reason) {
        this.failure = reason;
    }

    @Override
    public SystemToken tokenFor(TenantId tenant) {
        requests.add(tenant);
        if (failure != null) {
            throw new SystemTokenUnavailableException(failure);
        }
        return new SystemToken("token-" + requests.size(), clock.instant().plus(Duration.ofMinutes(15)));
    }

    @Override
    public void invalidate(TenantId tenant) {
        invalidations.add(tenant);
    }

    public List<TenantId> requests() {
        return List.copyOf(requests);
    }

    public List<TenantId> invalidations() {
        return List.copyOf(invalidations);
    }
}
