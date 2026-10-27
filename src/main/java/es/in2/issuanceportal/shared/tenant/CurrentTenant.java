package es.in2.issuanceportal.shared.tenant;

import jakarta.servlet.http.HttpServletRequest;

/** Reads the tenant {@link TenantFilter} resolved for the current API request. */
public final class CurrentTenant {

    private CurrentTenant() {
    }

    public static TenantId of(HttpServletRequest request) {
        if (request.getAttribute(TenantFilter.ATTRIBUTE) instanceof TenantId tenant) {
            return tenant;
        }
        throw new IllegalStateException("No tenant resolved for this request");
    }
}
