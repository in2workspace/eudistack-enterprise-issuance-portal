package es.in2.issuanceportal.journey.domain.config;

import java.util.Map;

/**
 * Portal configuration of a tenant (design.md AD-7). Valid by construction: the public part follows the
 * {@code PortalConfig} schema of the contract, and the wallet address is an https URL. Branding and
 * languages are not here: the interface takes them from the platform shared assets.
 *
 * @param texts          texts of each step, by language, served as they are
 * @param walletUrl      public address of the tenant's wallet, used when an offer has no onboarding address
 * @param assuranceLevel level of assurance of the tenant's login (AD-20); {@code high} when not configured
 * @param settings       what only the service reads
 */
public record PortalConfig(Map<String, Object> texts, LegalLinks legalLinks, Support support, String walletUrl,
                           AssuranceLevel assuranceLevel, ServiceSettings settings) {

    public PortalConfig {
        texts = ConfigValues.copy(ConfigValues.required(texts, "texts"));
        ConfigValues.required(legalLinks, "legal_links");
        ConfigValues.required(support, "support");
        ConfigValues.required(walletUrl, "wallet_url");
        ConfigValues.requireHttpsUrl(walletUrl, "wallet_url");
        assuranceLevel = assuranceLevel == null ? AssuranceLevel.HIGH : assuranceLevel;
        settings = settings == null ? ServiceSettings.NONE : settings;
    }

    /** A configuration without {@code assurance_level}: the login asks for {@code high}. */
    public PortalConfig(Map<String, Object> texts, LegalLinks legalLinks, Support support, String walletUrl,
                        ServiceSettings settings) {
        this(texts, legalLinks, support, walletUrl, AssuranceLevel.HIGH, settings);
    }
}
