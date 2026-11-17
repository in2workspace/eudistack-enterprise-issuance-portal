package es.in2.issuanceportal.journey.domain.config;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Portal configuration of a tenant (design.md AD-7). Valid by construction: the public part follows the
 * {@code PortalConfig} schema of the contract, and the wallet address is an https URL. Branding and
 * languages are not here: the interface takes them from the platform shared assets.
 *
 * @param texts          texts of each step, by language, served as they are
 * @param walletUrl      public address of the tenant's wallet, used when an offer has no onboarding address
 * @param assuranceLevel level of assurance of the tenant's login (AD-20); {@code high} when not configured
 * @param loginAttestationScope OAuth scope of the attestation the tenant accepts to log in with (AD-21); {@code null}
 *                       when the tenant only accepts the certificate
 * @param settings       what only the service reads
 */
public record PortalConfig(Map<String, Object> texts, LegalLinks legalLinks, Support support, String walletUrl,
                           AssuranceLevel assuranceLevel, String loginAttestationScope, ServiceSettings settings) {

    /** One scope token of RFC 6749 section 3.3: no space, no double quote, no backslash. */
    private static final Pattern SCOPE_TOKEN = Pattern.compile("[\\x21\\x23-\\x5B\\x5D-\\x7E]+");

    public PortalConfig {
        texts = ConfigValues.copy(ConfigValues.required(texts, "texts"));
        ConfigValues.required(legalLinks, "legal_links");
        ConfigValues.required(support, "support");
        ConfigValues.required(walletUrl, "wallet_url");
        ConfigValues.requireHttpsUrl(walletUrl, "wallet_url");
        assuranceLevel = assuranceLevel == null ? AssuranceLevel.HIGH : assuranceLevel;
        if (loginAttestationScope != null && !SCOPE_TOKEN.matcher(loginAttestationScope).matches()) {
            throw new InvalidPortalConfigException("login_attestation_scope must be one OAuth scope");
        }
        settings = settings == null ? ServiceSettings.NONE : settings;
    }

    /** A configuration without {@code login_attestation_scope}: the login is only with the certificate. */
    public PortalConfig(Map<String, Object> texts, LegalLinks legalLinks, Support support, String walletUrl,
                        AssuranceLevel assuranceLevel, ServiceSettings settings) {
        this(texts, legalLinks, support, walletUrl, assuranceLevel, null, settings);
    }

    /** A configuration without {@code assurance_level}: the login asks for {@code high}. */
    public PortalConfig(Map<String, Object> texts, LegalLinks legalLinks, Support support, String walletUrl,
                        ServiceSettings settings) {
        this(texts, legalLinks, support, walletUrl, AssuranceLevel.HIGH, null, settings);
    }
}
