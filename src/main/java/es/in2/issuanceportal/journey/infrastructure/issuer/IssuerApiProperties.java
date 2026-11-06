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
 * @param maxConcurrentPerTenant    how many issuance calls of one tenant may be in progress at once, retries
 *                                  included; another one fails at once without calling the Issuer (SEC-27)
 * @param circuitFailureRatePercent percentage of failed attempts (network errors, timeouts and 5xx; never a 4xx)
 *                                  among the last {@code circuitMinimumCalls} of a tenant that opens its circuit
 * @param circuitMinimumCalls       attempts of a tenant that must have been made before the circuit can open; it
 *                                  is also the size of the window the failure rate is measured over
 * @param circuitWaitInOpenState    how long an open circuit refuses calls before letting one through to test
 *                                  the Issuer (SEC-25)
 */
@Validated
@ConfigurationProperties("issuance-portal.issuance-api")
public record IssuerApiProperties(
        @NotBlank String issuerInternalUrl,
        @DefaultValue("PT10S") Duration requestTimeout,
        @DefaultValue("PT1S") Duration retryInitialWait,
        @DefaultValue("false") boolean retryOn503,
        @DefaultValue("20") int maxConcurrentPerTenant,
        @DefaultValue("50") int circuitFailureRatePercent,
        @DefaultValue("10") int circuitMinimumCalls,
        @DefaultValue("PT30S") Duration circuitWaitInOpenState) {

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
        if (maxConcurrentPerTenant < 1) {
            throw new IllegalArgumentException("max-concurrent-per-tenant must be at least 1");
        }
        if (circuitFailureRatePercent < 1 || circuitFailureRatePercent > 100) {
            throw new IllegalArgumentException("circuit-failure-rate-percent must be between 1 and 100");
        }
        if (circuitMinimumCalls < 1) {
            throw new IllegalArgumentException("circuit-minimum-calls must be at least 1");
        }
        if (circuitWaitInOpenState.isZero() || circuitWaitInOpenState.isNegative()) {
            throw new IllegalArgumentException("circuit-wait-in-open-state must be positive");
        }
    }
}
