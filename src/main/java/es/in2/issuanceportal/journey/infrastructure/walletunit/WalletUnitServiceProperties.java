package es.in2.issuanceportal.journey.infrastructure.walletunit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * How the portal calls the Wallet Unit Service. Where it is depends on the tenant ({@code onboarding} in its
 * portal configuration), so only the wait is set here.
 *
 * @param requestTimeout how long the Wallet Unit Service gets to answer (design.md §3.4: 5 s, no retries)
 */
@ConfigurationProperties("issuance-portal.wallet-unit-service")
public record WalletUnitServiceProperties(@DefaultValue("PT5S") Duration requestTimeout) {

    public WalletUnitServiceProperties {
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("request-timeout must be positive");
        }
    }
}
