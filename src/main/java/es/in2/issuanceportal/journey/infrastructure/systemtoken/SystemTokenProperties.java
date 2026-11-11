package es.in2.issuanceportal.journey.infrastructure.systemtoken;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/**
 * How the portal gets its system token. There is no default provider on purpose: a service without one
 * does not start.
 *
 * @param provider            where the machine credentials are read from
 * @param parameterTemplate   name of the tenant's SecureString parameter in SSM, with {@code {tenant}}
 * @param directory           folder with one {@code <tenant>.json} per tenant, for the file provider
 * @param verifierInternalUrl the Verifier as the portal reaches it on the internal network, with its context
 *                            path, e.g. {@code http://verifier:8082/verifier}
 * @param requestTimeout      how long the Verifier gets to answer
 */
@Validated
@ConfigurationProperties("issuance-portal.system-token")
public record SystemTokenProperties(
        @NotNull Provider provider,
        String parameterTemplate,
        Path directory,
        @NotBlank String verifierInternalUrl,
        @DefaultValue("PT5S") Duration requestTimeout) {

    static final String TENANT_PLACEHOLDER = "{tenant}";

    public enum Provider { SSM, FILE }

    public SystemTokenProperties {
        if (provider == Provider.SSM
                && (parameterTemplate == null || !parameterTemplate.contains(TENANT_PLACEHOLDER))) {
            throw new IllegalArgumentException("parameter-template with " + TENANT_PLACEHOLDER
                    + " is required with the ssm provider");
        }
        if (provider == Provider.FILE && (directory == null || directory.toString().isBlank())) {
            throw new IllegalArgumentException("directory is required with the file provider");
        }
        if (verifierInternalUrl != null && URI.create(verifierInternalUrl).getHost() == null) {
            throw new IllegalArgumentException("verifier-internal-url must be an absolute URL");
        }
    }
}
