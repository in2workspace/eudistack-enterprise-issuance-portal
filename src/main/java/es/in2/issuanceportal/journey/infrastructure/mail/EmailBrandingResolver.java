package es.in2.issuanceportal.journey.infrastructure.mail;

import es.in2.issuanceportal.journey.infrastructure.mail.EmailBrandingProperties.TenantEmailBranding;
import org.springframework.util.StringUtils;

/** Resolves the email branding of a tenant; tenants without a block get no images. */
class EmailBrandingResolver {

    private static final String LOGO_PATH = "email/logo.png";
    private static final String HEADER_BACKGROUND_PATH = "email/header-bg.png";

    private final EmailBrandingProperties properties;

    EmailBrandingResolver(EmailBrandingProperties properties) {
        this.properties = properties;
    }

    EmailBranding resolve(String tenantId) {
        TenantEmailBranding tenant = properties.tenants().get(tenantId);
        if (tenant == null) {
            return EmailBranding.NONE;
        }
        return new EmailBranding(
                tenant.logo() ? assetUrl(tenantId, LOGO_PATH) : null,
                tenant.headerBackground() ? assetUrl(tenantId, HEADER_BACKGROUND_PATH) : null);
    }

    private String assetUrl(String tenantId, String relativePath) {
        return StringUtils.trimTrailingCharacter(properties.assetsBaseUrl(), '/') + "/" + tenantId + "/" + relativePath;
    }
}
