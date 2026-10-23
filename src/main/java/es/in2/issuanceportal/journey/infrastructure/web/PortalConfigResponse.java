package es.in2.issuanceportal.journey.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import es.in2.issuanceportal.journey.domain.config.LegalLinks;
import es.in2.issuanceportal.journey.domain.config.PortalConfig;
import es.in2.issuanceportal.journey.domain.config.Support;

import java.util.Map;

/**
 * {@code PortalConfig} of the contract: only the public part. The service settings have no field here,
 * so they cannot leak to the browser.
 */
record PortalConfigResponse(
        Map<String, Object> texts,
        @JsonProperty("legal_links") LegalLinksResponse legalLinks,
        SupportResponse support,
        @JsonProperty("wallet_url") String walletUrl) {

    static PortalConfigResponse of(PortalConfig config) {
        return new PortalConfigResponse(config.texts(), LegalLinksResponse.of(config.legalLinks()),
                SupportResponse.of(config.support()), config.walletUrl());
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record LegalLinksResponse(@JsonProperty("legal_notice") String legalNotice, String privacy, String terms,
                              String accessibility) {
        static LegalLinksResponse of(LegalLinks links) {
            return new LegalLinksResponse(links.legalNotice(), links.privacy(), links.terms(), links.accessibility());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record SupportResponse(String email, String phone, String schedule,
                           @JsonProperty("response_time") String responseTime) {
        static SupportResponse of(Support support) {
            return new SupportResponse(support.email(), support.phone(), support.schedule(), support.responseTime());
        }
    }
}
