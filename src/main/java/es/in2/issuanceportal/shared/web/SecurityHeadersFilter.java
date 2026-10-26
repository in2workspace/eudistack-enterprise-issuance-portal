package es.in2.issuanceportal.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * The response headers every response must carry (conv-quality-security-gates §8.1), with the API
 * Content-Security-Policy. {@code Cache-Control: no-store} also keeps the edge from caching a journey,
 * which depends on the session cookie (conv-edge-caching, rule 5).
 *
 * <p>Runs first: the other filters answer some requests themselves (403, 404, 503), and those
 * responses need the headers too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    static final Map<String, String> HEADERS = Map.of(
            "Strict-Transport-Security", "max-age=31536000; includeSubDomains; preload",
            "Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'",
            "X-Content-Type-Options", "nosniff",
            "X-Frame-Options", "DENY",
            "Referrer-Policy", "strict-origin-when-cross-origin",
            "Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()",
            "Cache-Control", "no-store",
            "Pragma", "no-cache");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HEADERS.forEach(response::setHeader);
        chain.doFilter(request, response);
    }
}
