package es.in2.issuanceportal.shared.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;

import java.util.List;

/** An authenticated holder. It keeps the principal and not the token, so the token is never at hand to be logged. */
final class HolderAuthentication extends AbstractAuthenticationToken {

    private final transient HolderPrincipal principal;

    HolderAuthentication(HolderPrincipal principal) {
        super(List.of());
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public HolderPrincipal getPrincipal() {
        return principal;
    }

    /** Not the subject: this name ends up in logs and traces. */
    @Override
    public String getName() {
        return "holder";
    }
}
