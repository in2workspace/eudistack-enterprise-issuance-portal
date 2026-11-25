package es.in2.issuanceportal.authenticsource.application.port;

import es.in2.issuanceportal.shared.tenant.TenantId;

/** The credentials of the tenants' sources, kept outside the configuration (design.md AD-4). */
public interface SourceSecrets {

    /**
     * The credential kept under {@code reference}, read at the time of use. Only a reference under the tenant's own
     * prefix is read: a configuration can never reach the credentials of another tenant or of the service itself.
     *
     * @throws SourceSecretUnavailableException when it cannot be read, or the reference is outside the tenant's
     *                                          prefix; never with the secret
     */
    String secret(TenantId tenant, String reference);
}
