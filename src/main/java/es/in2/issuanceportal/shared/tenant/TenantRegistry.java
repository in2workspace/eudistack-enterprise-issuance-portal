package es.in2.issuanceportal.shared.tenant;

import java.util.List;

/** The platform's catalogue of tenants. This service only reads it. */
public interface TenantRegistry {

    boolean isActive(TenantId tenant);

    List<TenantId> activeTenants();
}
