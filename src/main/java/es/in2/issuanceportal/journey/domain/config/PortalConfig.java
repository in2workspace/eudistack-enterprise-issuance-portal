package es.in2.issuanceportal.journey.domain.config;

import java.util.Map;
import java.util.Set;

/**
 * Portal configuration of a tenant (design.md AD-7). Valid by construction: the public part follows the
 * {@code PortalConfig} schema of the contract, and the wallet address is an https URL. Branding and
 * languages are not here: the interface takes them from the platform shared assets.
 *
 * @param texts      texts of each step, by language, served as they are
 * @param walletUrl  public address of the tenant's wallet, used when an offer has no onboarding address
 * @param settings   what only the service reads
 */
public record PortalConfig(Map<String, Object> texts, LegalLinks legalLinks, Support support, String walletUrl,
                           ServiceSettings settings) {

    public PortalConfig {
        texts = ConfigValues.copy(ConfigValues.required(texts, "texts"));
        ConfigValues.required(legalLinks, "legal_links");
        ConfigValues.required(support, "support");
        ConfigValues.required(walletUrl, "wallet_url");
        ConfigValues.requireUrl(walletUrl, "wallet_url", Set.of("https"));
        settings = settings == null ? ServiceSettings.NONE : settings;
    }
}
