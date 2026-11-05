package es.in2.issuanceportal.journey.infrastructure.systemtoken;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import es.in2.issuanceportal.journey.application.port.SystemToken;
import es.in2.issuanceportal.journey.application.port.SystemTokenProvider;
import es.in2.issuanceportal.shared.tenant.TenantId;

import java.time.Clock;
import java.time.Duration;

/**
 * Keeps one token per tenant and reuses it until {@link #REUSE_MARGIN} or less is left (ac.md EC-05).
 * Concurrent requests of a tenant whose token is missing or about to expire share one call to the Verifier:
 * the others wait for it and use its token.
 */
final class CachingSystemTokenProvider implements SystemTokenProvider {

    /** A token with this much or less left is not reused. */
    static final Duration REUSE_MARGIN = Duration.ofSeconds(30);
    /** More tenants than any deployment has; it only keeps the cache bounded. */
    private static final long MAX_TENANTS = 1_000;

    private final TokenGrant grant;
    private final Clock clock;
    private final Cache<TenantId, SystemToken> tokens = Caffeine.newBuilder().maximumSize(MAX_TENANTS).build();

    CachingSystemTokenProvider(TokenGrant grant, Clock clock) {
        this.grant = grant;
        this.clock = clock;
    }

    @Override
    public SystemToken tokenFor(TenantId tenant) {
        SystemToken cached = tokens.getIfPresent(tenant);
        if (cached != null && reusable(cached)) {
            return cached;
        }
        // compute() is atomic per key: a second caller waits here and finds the first one's token.
        return tokens.asMap().compute(tenant, (key, current) ->
                current != null && reusable(current) ? current : grant.request(key));
    }

    @Override
    public void invalidate(TenantId tenant) {
        tokens.invalidate(tenant);
    }

    private boolean reusable(SystemToken token) {
        return token.expiresAt().isAfter(clock.instant().plus(REUSE_MARGIN));
    }
}
