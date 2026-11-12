package es.in2.issuanceportal.shared.security;

import com.nimbusds.jose.KeySourceException;
import es.in2.issuanceportal.session.api.IdentifierType;
import es.in2.issuanceportal.shared.error.ProblemCode;
import es.in2.issuanceportal.shared.error.ProblemException;
import es.in2.issuanceportal.shared.tenant.CurrentTenant;
import es.in2.issuanceportal.shared.tenant.TenantId;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

import java.util.List;

/**
 * Validates the Access Token of every request against the tenant of that request (design.md section 3.1, AD-9): the
 * tenant's decoder judges signature, {@code iss} and time, and the claims that depend on the tenant's portal
 * configuration or on the request are checked here: {@code aud}, {@code tenant} and {@code acr}. The result is the
 * {@link HolderPrincipal}.
 *
 * <p>Nothing of the token, the subject or the identity is logged: a rejection is logged as its kind only.
 */
@Slf4j
@Component
class TenantTokenAuthenticator implements AuthenticationManagerResolver<HttpServletRequest> {

    /** {@code acr} of a login with a high level of assurance (eIDAS). */
    static final String ACR_HIGH = "http://eidas.europa.eu/LoA/high";

    private final VerifierTokenDecoders decoders;
    private final TenantLoginSettings loginSettings;

    TenantTokenAuthenticator(VerifierTokenDecoders decoders, TenantLoginSettings loginSettings) {
        this.decoders = decoders;
        this.loginSettings = loginSettings;
    }

    @Override
    public AuthenticationManager resolve(HttpServletRequest request) {
        TenantId tenant = CurrentTenant.of(request);
        return authentication -> authenticate(tenant, (BearerTokenAuthenticationToken) authentication);
    }

    private Authentication authenticate(TenantId tenant, BearerTokenAuthenticationToken bearer) {
        LoginSettings settings = settingsOf(tenant);
        Jwt jwt = decode(tenant, bearer.getToken());
        List<String> audience = jwt.getAudience();
        if (audience == null || !audience.contains(settings.portalClientId())) {
            throw invalid("aud");
        }
        if (!tenant.value().equals(jwt.getClaimAsString("tenant"))) {
            throw invalid("tenant");
        }
        if (!ACR_HIGH.equals(jwt.getClaimAsString("acr"))) {
            throw invalid("acr");
        }
        return new HolderAuthentication(principalOf(tenant, jwt, settings));
    }

    private LoginSettings settingsOf(TenantId tenant) {
        LoginSettings settings;
        try {
            settings = loginSettings.of(tenant);
        } catch (ProblemException e) {
            throw new TokenUnavailableException(e.problem());
        } catch (DataAccessException | TransactionException e) {
            log.error("Portal configuration of tenant {} unavailable to validate a token: {}", tenant,
                    e.getClass().getSimpleName());
            throw new TokenUnavailableException(ProblemCode.SERVICE_UNAVAILABLE);
        }
        if (settings.portalClientId() == null || settings.portalClientId().isBlank()) {
            log.warn("Tenant {} has no portal_client_id in its portal configuration: its tokens cannot be validated",
                    tenant);
            throw new TokenUnavailableException(ProblemCode.CONFIG_UNAVAILABLE);
        }
        return settings;
    }

    private Jwt decode(TenantId tenant, String token) {
        try {
            return decoders.forTenant(tenant).decode(token);
        } catch (BadJwtException e) {
            throw invalid(e.getClass().getSimpleName());
        } catch (JwtException e) {
            if (causedByKeySource(e)) {
                log.warn("The keys of the Verifier of tenant {} are not available to validate a token", tenant);
                throw new TokenUnavailableException(ProblemCode.SERVICE_UNAVAILABLE);
            }
            throw invalid(e.getClass().getSimpleName());
        }
    }

    private HolderPrincipal principalOf(TenantId tenant, Jwt jwt, LoginSettings settings) {
        String credentialType = jwt.getClaimAsString("credential_type");
        List<String> methods = jwt.getClaimAsStringList("amr");
        IdentifierType type = PersonalIdentifierTypes
                .resolve(credentialType, methods == null ? List.of() : methods, settings.identifierTypes())
                .orElseThrow(() -> {
                    log.warn("A valid token of tenant {} gives no personal identifier type (credential_type present: {}):"
                            + " check identifier_types in its portal configuration", tenant, credentialType != null);
                    return invalid("identifier type");
                });
        return new HolderPrincipal(jwt.getClaimAsString("iss"), jwt.getSubject(), jwt.getClaimAsString("given_name"),
                jwt.getClaimAsString("family_name"), jwt.getClaimAsString("personal_administrative_number"),
                credentialType, methods, type);
    }

    private static boolean causedByKeySource(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof KeySourceException) {
                return true;
            }
        }
        return false;
    }

    /** What failed, never the value: {@code reason} is a claim name or the kind of the decoder's exception. */
    private static AuthenticationException invalid(String reason) {
        log.debug("Access token rejected: {}", reason);
        return new InvalidBearerTokenException("The access token is not valid");
    }
}
