package es.in2.issuanceportal.journey.infrastructure.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Map;

/**
 * Branding of the code email, per tenant. Same keys and file shape as the wallet's {@code email-branding.yaml},
 * so one file can serve both services; the keys this service does not use (social links, support links) are
 * ignored. Image locations are not configured: they follow the fixed names {@code email/logo.png} and
 * {@code email/header-bg.png} inside the tenant folder under {@code assetsBaseUrl}.
 *
 * @param assetsBaseUrl the tenant-assets root of the environment; required with the smtp mail provider
 */
@ConfigurationProperties(prefix = "email-branding")
public record EmailBrandingProperties(String assetsBaseUrl, @DefaultValue Map<String, TenantEmailBranding> tenants) {

    /** A tenant's own branding: the image shows only when its flag is set. */
    public record TenantEmailBranding(@DefaultValue("false") boolean logo,
                                      @DefaultValue("false") boolean headerBackground) {
    }
}
