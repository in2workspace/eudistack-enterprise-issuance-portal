package es.in2.issuanceportal.shared.tenant;

import es.in2.issuanceportal.shared.error.ProblemCode;
import es.in2.issuanceportal.shared.error.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Resolves the tenant of every API request, with the same rule as nginx and the Verifier: the
 * {@code X-Tenant} header when nginx sends it (custom domains), otherwise the first segment of the
 * host. An unknown or inactive tenant gets 404 {@code tenant_not_found}.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TenantFilter extends OncePerRequestFilter {

    static final String TENANT_HEADER = "X-Tenant";
    static final String ATTRIBUTE = TenantFilter.class.getName() + ".tenant";
    /** OTel semantic name, as conv-observability §2 expects it in every log line. */
    private static final String MDC_KEY = "tenant.id";
    private static final String API_PATH = "/api/";

    private final TenantRegistry registry;

    public TenantFilter(TenantRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + API_PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<TenantId> tenant = resolve(request).filter(registry::isActive);
        if (tenant.isEmpty()) {
            log.warn("Rejected request for an unknown or inactive tenant");
            Problems.write(response, ProblemCode.TENANT_NOT_FOUND);
            return;
        }
        request.setAttribute(ATTRIBUTE, tenant.get());
        MDC.put(MDC_KEY, tenant.get().value());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    static Optional<TenantId> resolve(HttpServletRequest request) {
        String header = request.getHeader(TENANT_HEADER);
        if (header != null && !header.isBlank()) {
            return TenantId.parse(header);
        }
        String host = request.getServerName();
        if (host == null) {
            return Optional.empty();
        }
        int dot = host.indexOf('.');
        return dot > 0 ? TenantId.parse(host.substring(0, dot)) : Optional.empty();
    }
}
