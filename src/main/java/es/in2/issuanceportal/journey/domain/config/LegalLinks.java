package es.in2.issuanceportal.journey.domain.config;

/** Legal pages of the tenant. Every link is optional in the contract, and a URL when present. */
public record LegalLinks(String legalNotice, String privacy, String terms, String accessibility) {

    public LegalLinks {
        ConfigValues.requireWebUrl(legalNotice, "legal_links.legal_notice");
        ConfigValues.requireWebUrl(privacy, "legal_links.privacy");
        ConfigValues.requireWebUrl(terms, "legal_links.terms");
        ConfigValues.requireWebUrl(accessibility, "legal_links.accessibility");
    }
}
