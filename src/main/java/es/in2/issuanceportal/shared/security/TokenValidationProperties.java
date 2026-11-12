package es.in2.issuanceportal.shared.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

/**
 * How the Access Token is validated (design.md AD-9).
 *
 * @param verifierInternalUrl   the Verifier as the portal reaches it on the internal network, with its context path,
 *                              e.g. {@code http://verifier:8082/verifier}: the same one the system token uses
 * @param jwksCacheTtl          how long a tenant's JWKS is kept
 * @param jwksMinReloadInterval least time between two reloads of a tenant's JWKS caused by an unknown {@code kid},
 *                              so a flood of forged tokens cannot become a flood of requests to the Verifier; zero
 *                              turns the limit off
 * @param requestTimeout        how long the Verifier gets to answer the discovery and the JWKS
 */
@Validated
@ConfigurationProperties("issuance-portal.token-validation")
public record TokenValidationProperties(
        @NotBlank String verifierInternalUrl,
        @DefaultValue("PT10M") Duration jwksCacheTtl,
        @DefaultValue("PT5S") Duration jwksMinReloadInterval,
        @DefaultValue("PT5S") Duration requestTimeout) {

    public TokenValidationProperties {
        if (verifierInternalUrl != null && URI.create(verifierInternalUrl).getHost() == null) {
            throw new IllegalArgumentException("verifier-internal-url must be an absolute URL");
        }
        if (jwksCacheTtl.isNegative() || jwksCacheTtl.isZero()) {
            throw new IllegalArgumentException("jwks-cache-ttl must be positive");
        }
        if (jwksMinReloadInterval.isNegative()) {
            throw new IllegalArgumentException("jwks-min-reload-interval cannot be negative");
        }
    }
}
