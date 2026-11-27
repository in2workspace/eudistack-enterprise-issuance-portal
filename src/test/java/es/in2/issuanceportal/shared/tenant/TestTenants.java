package es.in2.issuanceportal.shared.tenant;

import jakarta.servlet.http.HttpServletRequest;

/** Lets a test outside this package hand over a request the tenant filter has already resolved. */
public final class TestTenants {

    private TestTenants() {
    }

    public static void resolved(HttpServletRequest request, TenantId tenant) {
        request.setAttribute(TenantFilter.ATTRIBUTE, tenant);
    }
}
