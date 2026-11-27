package es.in2.issuanceportal.shared.tenant;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;

/** Reads the tenant {@link TenantFilter} resolved for the current API request. */
public final class CurrentTenant {

    private CurrentTenant() {
    }

    public static TenantId of(HttpServletRequest request) {
        return find(request).orElseThrow(() -> new IllegalStateException("No tenant resolved for this request"));
    }

    /** Empty for a request the filter resolved no tenant for, such as health or metrics. */
    public static Optional<TenantId> find(HttpServletRequest request) {
        return request.getAttribute(TenantFilter.ATTRIBUTE) instanceof TenantId tenant
                ? Optional.of(tenant) : Optional.empty();
    }
}
