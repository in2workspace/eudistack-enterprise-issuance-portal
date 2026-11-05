package es.in2.issuanceportal.journey.infrastructure.issuer;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

/**
 * How the portal calls the Issuer's issuance API.
 *
 * @param issuerInternalUrl the Issuer as the portal reaches it on the internal network, with its context path,
 *                          e.g. {@code http://issuer-service:8080/issuer}
 * @param requestTimeout    how long the Issuer gets to answer each call (EC-07)
 * @param retryInitialWait  wait before the first retry when the Issuer sends no {@code Retry-After}; it doubles
 *                          on each retry (1, 2 and 4 s)
 * @param retryOn503        whether a 503 is retried. Off until the Issuer shares its idempotency between
 *                          instances (EUD-254); network errors and timeouts are always retried
 */
@Validated
@ConfigurationProperties("issuance-portal.issuance-api")
public record IssuerApiProperties(
        @NotBlank String issuerInternalUrl,
        @DefaultValue("PT10S") Duration requestTimeout,
        @DefaultValue("PT1S") Duration retryInitialWait,
        @DefaultValue("false") boolean retryOn503) {

    public IssuerApiProperties {
        if (issuerInternalUrl != null && URI.create(issuerInternalUrl).getHost() == null) {
            throw new IllegalArgumentException("issuer-internal-url must be an absolute URL");
        }
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("request-timeout must be positive");
        }
        if (retryInitialWait.isNegative()) {
            throw new IllegalArgumentException("retry-initial-wait must not be negative");
        }
    }
}
