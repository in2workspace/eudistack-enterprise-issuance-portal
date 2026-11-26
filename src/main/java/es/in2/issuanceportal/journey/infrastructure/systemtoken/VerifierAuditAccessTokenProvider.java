package es.in2.issuanceportal.journey.infrastructure.systemtoken;

import com.eudistack.auditoria.port.AuditAccessTokenProvider;
import es.in2.issuanceportal.shared.systemtoken.SystemTokenProvider;
import es.in2.issuanceportal.shared.tenant.TenantId;
import lombok.RequiredArgsConstructor;

/**
 * The token the audit dispatcher sends to the Audit API. The requested audience cannot be honoured: the Verifier
 * puts its own URL in the {@code aud} of a machine token, so that is what the Audit API expects.
 */
@RequiredArgsConstructor
final class VerifierAuditAccessTokenProvider implements AuditAccessTokenProvider {

    private final SystemTokenProvider tokens;
    private final TenantId tenant;

    @Override
    public String accessToken(String audience) {
        return tokens.tokenFor(tenant).value();
    }

    @Override
    public String refreshAccessToken(String audience) {
        tokens.invalidate(tenant);
        return accessToken(audience);
    }
}
