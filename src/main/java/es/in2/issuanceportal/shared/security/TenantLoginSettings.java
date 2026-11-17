package es.in2.issuanceportal.shared.security;

import es.in2.issuanceportal.shared.tenant.TenantId;

/** Port towards the tenant's portal configuration, so that the token validation does not know the Journey module. */
public interface TenantLoginSettings {

    /**
     * @throws es.in2.issuanceportal.shared.error.ProblemException {@code config_unavailable} when the tenant has no
     *                                                             valid configuration
     */
    LoginSettings of(TenantId tenant);
}
