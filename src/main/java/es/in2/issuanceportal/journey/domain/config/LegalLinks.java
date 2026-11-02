package es.in2.issuanceportal.journey.domain.config;

/** Legal pages of the tenant. Every link is optional in the contract, and a URL when present. */
public record LegalLinks(String legalNotice, String privacy, String terms, String accessibility) {

    public LegalLinks {
        ConfigValues.requireHttpsUrl(legalNotice, "legal_links.legal_notice");
        ConfigValues.requireHttpsUrl(privacy, "legal_links.privacy");
        ConfigValues.requireHttpsUrl(terms, "legal_links.terms");
        ConfigValues.requireHttpsUrl(accessibility, "legal_links.accessibility");
    }
}
