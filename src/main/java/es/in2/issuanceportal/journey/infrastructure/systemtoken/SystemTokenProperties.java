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
 * @param secretNameTemplate  name of the tenant's secret in Secrets Manager, with {@code {tenant}}
 * @param directory           folder with one {@code <tenant>.json} per tenant, for the file provider
 * @param verifierInternalUrl the Verifier as the portal reaches it on the internal network, with its context
 *                            path, e.g. {@code http://verifier:8082/verifier}
 * @param publicHostTemplate  the tenant's public host, with {@code {tenant}}. It travels as
 *                            {@code X-Forwarded-Host}, so the Verifier knows its public address and the
 *                            token's {@code iss} is {@code https://<public host><context path>}
 * @param requestTimeout      how long the Verifier gets to answer
 */
@Validated
@ConfigurationProperties("issuance-portal.system-token")
public record SystemTokenProperties(
        @NotNull Provider provider,
        String secretNameTemplate,
        Path directory,
        @NotBlank String verifierInternalUrl,
        @NotBlank String publicHostTemplate,
        @DefaultValue("PT5S") Duration requestTimeout) {

    static final String TENANT_PLACEHOLDER = "{tenant}";

    public enum Provider { SECRETS_MANAGER, FILE }

    public SystemTokenProperties {
        if (provider == Provider.SECRETS_MANAGER
                && (secretNameTemplate == null || !secretNameTemplate.contains(TENANT_PLACEHOLDER))) {
            throw new IllegalArgumentException("secret-name-template with " + TENANT_PLACEHOLDER
                    + " is required with the secrets-manager provider");
        }
        if (provider == Provider.FILE && (directory == null || directory.toString().isBlank())) {
            throw new IllegalArgumentException("directory is required with the file provider");
        }
        if (publicHostTemplate != null && !publicHostTemplate.contains(TENANT_PLACEHOLDER)) {
            throw new IllegalArgumentException("public-host-template must contain " + TENANT_PLACEHOLDER);
        }
        if (verifierInternalUrl != null && URI.create(verifierInternalUrl).getHost() == null) {
            throw new IllegalArgumentException("verifier-internal-url must be an absolute URL");
        }
    }
}
