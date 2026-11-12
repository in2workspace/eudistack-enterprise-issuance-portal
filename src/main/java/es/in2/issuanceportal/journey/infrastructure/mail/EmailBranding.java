package es.in2.issuanceportal.journey.infrastructure.mail;

/**
 * Brand-specific items of the code email, already resolved to absolute URLs for a tenant. Each is {@code null}
 * when the tenant has no such image: the template then omits it.
 */
record EmailBranding(String logoUrl, String headerBackgroundUrl) {

    static final EmailBranding NONE = new EmailBranding(null, null);
}
