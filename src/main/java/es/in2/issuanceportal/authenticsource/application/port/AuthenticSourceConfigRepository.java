package es.in2.issuanceportal.authenticsource.application.port;

import es.in2.issuanceportal.authenticsource.domain.config.AuthenticSourceConfig;
import es.in2.issuanceportal.shared.tenant.TenantId;

import java.util.Optional;

/** The module's part of the tenant's {@code portal_config}. Empty when the row is missing or invalid. */
public interface AuthenticSourceConfigRepository {

    Optional<AuthenticSourceConfig> find(TenantId tenant);
}
