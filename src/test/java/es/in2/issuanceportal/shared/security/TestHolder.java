package es.in2.issuanceportal.shared.security;

import es.in2.issuanceportal.session.api.HolderRef;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.context.support.WebApplicationContextUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * A holder of a test: the subject their Access Tokens carry and the reference the service derives from it. Each request
 * mints a token valid for the context's clock, so a test that moves the clock keeps its holder.
 */
public final class TestHolder {

    private final TenantId tenant;
    private final String subject;
    private final HolderRef ref;

    private TestHolder(TenantId tenant, String subject, HolderRef ref) {
        this.tenant = tenant;
        this.subject = subject;
        this.ref = ref;
    }

    /** A holder who has not been seen before. */
    public static TestHolder of(TenantId tenant, HolderRefs refs) {
        return of(tenant, "holder-" + UUID.randomUUID(), refs);
    }

    public static TestHolder of(TenantId tenant, String subject, HolderRefs refs) {
        HolderPrincipal principal = new HolderPrincipal(AccessTokens.issuerOf(tenant), subject, null, null, null, null,
                List.of(), null);
        return new TestHolder(tenant, subject, refs.of(principal));
    }

    /** What the service calls this holder. */
    public HolderRef ref() {
        return ref;
    }

    public String subject() {
        return subject;
    }

    /** The holder's token with the default claims of {@link AccessTokens}. */
    public RequestPostProcessor token() {
        return token(tokens -> tokens);
    }

    /** The holder's token with claims a scenario changes. */
    public RequestPostProcessor token(UnaryOperator<AccessTokens> claims) {
        return request -> {
            Clock clock = WebApplicationContextUtils.getRequiredWebApplicationContext(request.getServletContext())
                    .getBean(Clock.class);
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + mint(clock.instant(), claims));
            return request;
        };
    }

    /** The compact token as of {@code now}, for a test that needs to know it. */
    public String mint(Instant now, UnaryOperator<AccessTokens> claims) {
        return claims.apply(AccessTokens.forTenant(tenant, now).claim("sub", subject)).sign();
    }
}
