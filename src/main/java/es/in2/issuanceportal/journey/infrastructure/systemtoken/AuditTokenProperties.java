package es.in2.issuanceportal.journey.infrastructure.systemtoken;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * How the portal gets its token for the Audit API: its audit attestation, one per environment, comes from the
 * environment. Nothing here is checked at binding: with the audit dispatcher off none of it is needed, and
 * {@link AuditTokenConfiguration} validates it when the dispatcher is on.
 *
 * @param tokenUrl          the Verifier's token endpoint on the internal network
 * @param tenant            the tenant the token is asked in, the one of the attestation's power domain
 * @param assertionAudience the Verifier's URL as it is called, the {@code aud} of the client assertion
 * @param clientId          the mandatee of the attestation
 * @param credential        the attestation's JWT
 * @param privateKey        the mandatee's P-256 key, as a JWK or a hex scalar
 * @param requestTimeout    how long the Verifier gets to answer
 */
@ConfigurationProperties("issuance-portal.audit-token")
public record AuditTokenProperties(
        String tokenUrl,
        String tenant,
        String assertionAudience,
        String clientId,
        String credential,
        String privateKey,
        @DefaultValue("PT5S") Duration requestTimeout) {

    @Override
    public String toString() {
        return "AuditTokenProperties[tokenUrl=" + tokenUrl + ", tenant=" + tenant + ", credential and key redacted]";
    }
}
