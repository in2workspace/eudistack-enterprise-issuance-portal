package es.in2.issuanceportal.journey.infrastructure.persistence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import es.in2.issuanceportal.journey.domain.config.LegalLinks;
import es.in2.issuanceportal.journey.domain.config.Onboarding;
import es.in2.issuanceportal.journey.domain.config.PortalConfig;
import es.in2.issuanceportal.journey.domain.config.ServiceSettings;
import es.in2.issuanceportal.journey.domain.config.Support;

import java.util.Map;

/** Shape of {@code portal_config.config}: the contract's names, snake_case, unknown keys ignored. */
@JsonIgnoreProperties(ignoreUnknown = true)
record PortalConfigDocument(
        Map<String, Object> texts,
        @JsonProperty("legal_links") LegalLinksDocument legalLinks,
        SupportDocument support,
        @JsonProperty("wallet_url") String walletUrl,
        @JsonProperty("attestation_type") String attestationType,
        @JsonProperty("data_placement") Map<String, String> dataPlacement,
        OnboardingDocument onboarding,
        @JsonProperty("portal_client_id") String portalClientId,
        @JsonProperty("identifier_types") Map<String, String> identifierTypes) {

    /** Builds the domain configuration, which validates it. */
    PortalConfig toDomain() {
        return new PortalConfig(
                texts,
                legalLinks == null ? null : legalLinks.toDomain(),
                support == null ? null : support.toDomain(),
                walletUrl,
                new ServiceSettings(attestationType, dataPlacement,
                        onboarding == null ? null : onboarding.toDomain(), portalClientId, identifierTypes));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LegalLinksDocument(@JsonProperty("legal_notice") String legalNotice, String privacy, String terms,
                              String accessibility) {
        LegalLinks toDomain() {
            return new LegalLinks(legalNotice, privacy, terms, accessibility);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SupportDocument(String email, String phone, String schedule,
                           @JsonProperty("response_time") String responseTime) {
        Support toDomain() {
            return new Support(email, phone, schedule, responseTime);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OnboardingDocument(boolean enabled,
                              @JsonProperty("wallet_unit_service_url") String walletUnitServiceUrl) {
        Onboarding toDomain() {
            return new Onboarding(enabled, walletUnitServiceUrl);
        }
    }
}
