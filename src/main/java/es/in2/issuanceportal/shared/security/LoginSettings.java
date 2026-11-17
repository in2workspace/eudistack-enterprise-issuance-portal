package es.in2.issuanceportal.shared.security;

import java.util.Map;
import java.util.Set;

/**
 * What the token validation reads of the tenant's portal configuration.
 *
 * @param portalClientId  {@code portal_client_id}: the {@code client_id} of the portal at the tenant's Verifier,
 *                        which the Access Token's {@code aud} must contain; {@code null} when not configured
 * @param identifierTypes {@code identifier_types}: attestation type accepted at login → personal identifier type
 * @param acceptedAcr     {@code acr} values the Access Token may carry: those of the tenant's level of assurance
 *                        and higher (design.md AD-20); only the high level when not given
 */
public record LoginSettings(String portalClientId, Map<String, String> identifierTypes, Set<String> acceptedAcr) {

    /** {@code acr} of a login with a high level of assurance (eIDAS). */
    static final String ACR_HIGH = "http://eidas.europa.eu/LoA/high";

    public LoginSettings {
        identifierTypes = identifierTypes == null ? Map.of() : Map.copyOf(identifierTypes);
        acceptedAcr = acceptedAcr == null || acceptedAcr.isEmpty() ? Set.of(ACR_HIGH) : Set.copyOf(acceptedAcr);
    }
}
