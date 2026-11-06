package es.in2.issuanceportal.shared.tenant;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The public address of each tenant's platform services. The portal calls the Verifier and the Issuer on the
 * internal network, but they resolve their public origin from {@code X-Forwarded-Host} and
 * {@code X-Forwarded-Proto}: the token's {@code iss} and the {@code Location} of an issuance are built from it.
 *
 * @param template the tenant's public host with {@code {tenant}}, e.g. {@code {tenant}.127.0.0.1.nip.io:4443}
 */
@Validated
@ConfigurationProperties("issuance-portal.public-host")
public record PublicHostProperties(@NotBlank String template) {

    static final String TENANT_PLACEHOLDER = "{tenant}";

    public PublicHostProperties {
        if (template != null && !template.contains(TENANT_PLACEHOLDER)) {
            throw new IllegalArgumentException("public-host.template must contain " + TENANT_PLACEHOLDER);
        }
    }

    /** The tenant's public host, with its port if it has one. */
    public String hostOf(TenantId tenant) {
        return template.replace(TENANT_PLACEHOLDER, tenant.value());
    }
}
