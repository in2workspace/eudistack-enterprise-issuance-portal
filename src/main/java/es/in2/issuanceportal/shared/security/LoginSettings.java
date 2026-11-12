package es.in2.issuanceportal.shared.security;

import java.util.Map;

/**
 * What the token validation reads of the tenant's portal configuration.
 *
 * @param portalClientId  {@code portal_client_id}: the {@code client_id} of the portal at the tenant's Verifier,
 *                        which the Access Token's {@code aud} must contain; {@code null} when not configured
 * @param identifierTypes {@code identifier_types}: attestation type accepted at login → personal identifier type
 */
public record LoginSettings(String portalClientId, Map<String, String> identifierTypes) {

    public LoginSettings {
        identifierTypes = identifierTypes == null ? Map.of() : Map.copyOf(identifierTypes);
    }
}
