package es.in2.issuanceportal.journey.application.port;

import java.util.Map;
import java.util.Objects;

/**
 * What the portal asks the Issuer to issue (design.md §3.3). It holds personal data: {@link #toString()} shows none.
 *
 * @param credentialConfigurationId the tenant's attestation type
 * @param payload                   the attestation's claims
 * @param email                     where the Issuer delivers the offer
 * @param delivery                  the delivery channels, {@code ui} for the portal
 * @param grantType                 the OID4VCI grant, {@code authorization_code} for the portal
 */
public record IssuanceRequest(String credentialConfigurationId, Map<String, Object> payload, String email,
                              String delivery, String grantType) {

    public IssuanceRequest {
        Objects.requireNonNull(credentialConfigurationId);
        Objects.requireNonNull(payload);
        Objects.requireNonNull(email);
        Objects.requireNonNull(delivery);
        Objects.requireNonNull(grantType);
        payload = Map.copyOf(payload);
    }

    @Override
    public String toString() {
        return "IssuanceRequest[credentialConfigurationId=" + credentialConfigurationId + ", delivery=" + delivery
                + ", grantType=" + grantType + "]";
    }
}
