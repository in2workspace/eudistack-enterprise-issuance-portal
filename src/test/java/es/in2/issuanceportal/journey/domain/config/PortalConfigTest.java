package es.in2.issuanceportal.journey.domain.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortalConfigTest {

    private static final Map<String, Object> TEXTS = Map.of("es", Map.of("portal.app_name", "Portal"));
    private static final LegalLinks LEGAL_LINKS = new LegalLinks(
            "https://example.org/aviso-legal", "https://example.org/privacidad", null, null);
    private static final Support SUPPORT = new Support("soporte@example.org", "900 000 000", "L-V 9:00-18:00", "48 horas");
    private static final String WALLET_URL = "https://cgcom.stg.eudistack.net/wallet";

    @Test
    void portalConfig_validContract_keepsEveryPart() {
        PortalConfig config = new PortalConfig(TEXTS, LEGAL_LINKS, SUPPORT, WALLET_URL, null);

        assertThat(config.texts()).isEqualTo(TEXTS);
        assertThat(config.legalLinks()).isEqualTo(LEGAL_LINKS);
        assertThat(config.support()).isEqualTo(SUPPORT);
        assertThat(config.walletUrl()).isEqualTo(WALLET_URL);
    }

    @Test
    void portalConfig_withoutSettings_hasNoneWithOnboardingDisabled() {
        PortalConfig config = new PortalConfig(TEXTS, LEGAL_LINKS, SUPPORT, WALLET_URL, null);

        assertThat(config.settings()).isEqualTo(ServiceSettings.NONE);
        assertThat(config.settings().onboarding().enabled()).isFalse();
        assertThat(config.settings().dataPlacement()).isEmpty();
        assertThat(config.settings().identifierTypes()).isEmpty();
    }

    @Test
    void portalConfig_withSettings_keepsThem() {
        ServiceSettings settings = new ServiceSettings("doctorid.sd.1", Map.of("givenName", "identity.given_name"),
                new Onboarding(true, "https://wallet-unit.internal"), "issuance-portal-cgcom",
                Map.of("doctorid.sd.1", "colegiado"));

        PortalConfig config = new PortalConfig(TEXTS, LEGAL_LINKS, SUPPORT, WALLET_URL, settings);

        assertThat(config.settings()).isEqualTo(settings);
        assertThat(config.settings().onboarding().enabled()).isTrue();
    }

    @Test
    void portalConfig_emptySupportAndLegalLinks_isValid() {
        PortalConfig config = new PortalConfig(TEXTS, new LegalLinks(null, null, null, null),
                new Support(null, null, null, null), WALLET_URL, null);

        assertThat(config.support().email()).isNull();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"http://cgcom.stg.eudistack.net/wallet", "/wallet", "https:///wallet", "not a url", "ftp://cgcom/wallet"})
    void portalConfig_walletUrlMissingOrNotHttps_isInvalid(String walletUrl) {
        assertThatThrownBy(() -> new PortalConfig(TEXTS, LEGAL_LINKS, SUPPORT, walletUrl, null))
                .isInstanceOf(InvalidPortalConfigException.class)
                .hasMessageContaining("wallet_url");
    }

    @Test
    void portalConfig_withoutTexts_isInvalid() {
        assertThatThrownBy(() -> new PortalConfig(null, LEGAL_LINKS, SUPPORT, WALLET_URL, null))
                .isInstanceOf(InvalidPortalConfigException.class)
                .hasMessageContaining("texts");
    }

    @Test
    void portalConfig_withoutLegalLinks_isInvalid() {
        assertThatThrownBy(() -> new PortalConfig(TEXTS, null, SUPPORT, WALLET_URL, null))
                .isInstanceOf(InvalidPortalConfigException.class)
                .hasMessageContaining("legal_links");
    }

    @Test
    void portalConfig_withoutSupport_isInvalid() {
        assertThatThrownBy(() -> new PortalConfig(TEXTS, LEGAL_LINKS, null, WALLET_URL, null))
                .isInstanceOf(InvalidPortalConfigException.class)
                .hasMessageContaining("support");
    }

    @ParameterizedTest
    @ValueSource(strings = {"aviso-legal", "javascript:alert(1)", "https://"})
    void legalLinks_notAWebUrl_isInvalid(String link) {
        assertThatThrownBy(() -> new LegalLinks(null, link, null, null))
                .isInstanceOf(InvalidPortalConfigException.class)
                .hasMessageContaining("legal_links.privacy");
    }

    @ParameterizedTest
    @ValueSource(strings = {"soporte", "soporte@", "soporte@example", "sop orte@example.org"})
    void support_emailWithoutFormat_isInvalid(String email) {
        assertThatThrownBy(() -> new Support(email, null, null, null))
                .isInstanceOf(InvalidPortalConfigException.class)
                .hasMessageContaining("support.email");
    }

    @Test
    void onboarding_walletUnitServiceUrlNotAUrl_isInvalid() {
        assertThatThrownBy(() -> new Onboarding(true, "wallet-unit"))
                .isInstanceOf(InvalidPortalConfigException.class)
                .hasMessageContaining("onboarding.wallet_unit_service_url");
    }

    @Test
    void invalidPortalConfig_message_namesTheFieldNotTheValue() {
        assertThatThrownBy(() -> new Support("secret-value", null, null, null))
                .hasMessageNotContaining("secret-value");
    }
}
