package es.in2.issuanceportal.shared.web;

import es.in2.issuanceportal.shared.error.ProblemCode;
import es.in2.issuanceportal.shared.error.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Requests that change state must carry {@code X-Requested-With: issuance-portal}. A form posted from
 * another site cannot send a custom header, which closes the cross-site request path left by the
 * SameSite=Lax cookie (design.md AD-9). Runs before the tenant filter, so a rejected request never
 * reaches the database.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class RequestedWithFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Requested-With";
    static final String EXPECTED = "issuance-portal";
    private static final Set<String> STATE_CHANGING = Set.of("POST", "PUT", "PATCH", "DELETE");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !STATE_CHANGING.contains(request.getMethod()) || OperationalPaths.isOperational(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!EXPECTED.equals(request.getHeader(HEADER))) {
            Problems.write(response, ProblemCode.REQUEST_NOT_ALLOWED);
            return;
        }
        chain.doFilter(request, response);
    }
}
