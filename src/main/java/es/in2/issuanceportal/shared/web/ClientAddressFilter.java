package es.in2.issuanceportal.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves the client's address from {@code X-Forwarded-For} counting only the proxies of the platform:
 * each one appends the address it received the request from, so the client is the entry just before the
 * last {@code trustedProxies} hops. Whatever the client wrote further left is ignored. Locally the only
 * proxy is nginx (1); in AWS they are CloudFront and the ALB (2).
 *
 * <p>Runs before Spring's {@code ForwardedHeaderFilter} and hides {@code X-Forwarded-For} and
 * {@code Forwarded} from it, so that filter cannot replace the address with the leftmost, client-written
 * entry. It still applies {@code X-Forwarded-Host} and {@code X-Forwarded-Proto}.
 */
public class ClientAddressFilter extends OncePerRequestFilter {

    static final String X_FORWARDED_FOR = "X-Forwarded-For";
    private static final Set<String> HIDDEN = Set.of("x-forwarded-for", "forwarded");

    private final int trustedProxies;

    public ClientAddressFilter(int trustedProxies) {
        if (trustedProxies < 0) {
            throw new IllegalArgumentException("issuance-portal.client-address.trusted-proxies must be 0 or more");
        }
        this.trustedProxies = trustedProxies;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(new ClientAddressRequest(request, clientAddress(request)), response);
    }

    /**
     * The chain of addresses is every {@code X-Forwarded-For} entry followed by the connection's peer. With
     * fewer entries than trusted proxies the request skipped one of them: the leftmost entry is the best guess.
     * The entry must be an IP literal: otherwise the nearest valid entry to its right, which a proxy wrote,
     * or the peer. Returned in canonical form, so one address never counts as several.
     */
    String clientAddress(HttpServletRequest request) {
        if (trustedProxies == 0) {
            return request.getRemoteAddr();
        }
        List<String> chain = new ArrayList<>();
        for (String header : Collections.list(request.getHeaders(X_FORWARDED_FOR))) {
            for (String entry : header.split(",")) {
                if (!entry.isBlank()) {
                    chain.add(entry.strip());
                }
            }
        }
        chain.add(request.getRemoteAddr());
        for (int i = Math.max(0, chain.size() - 1 - trustedProxies); i < chain.size(); i++) {
            Optional<String> address = ipLiteral(chain.get(i));
            if (address.isPresent()) {
                return address.get();
            }
        }
        return request.getRemoteAddr();
    }

    /** Parses without any DNS lookup; a host name, a port or any other text is not an address. */
    private static Optional<String> ipLiteral(String entry) {
        try {
            return Optional.of(InetAddress.ofLiteral(entry).getHostAddress());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static final class ClientAddressRequest extends HttpServletRequestWrapper {

        private final String clientAddress;

        private ClientAddressRequest(HttpServletRequest request, String clientAddress) {
            super(request);
            this.clientAddress = clientAddress;
        }

        @Override
        public String getRemoteAddr() {
            return clientAddress;
        }

        @Override
        public String getRemoteHost() {
            return clientAddress;
        }

        @Override
        public String getHeader(String name) {
            return hidden(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return hidden(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            return Collections.enumeration(Collections.list(super.getHeaderNames()).stream()
                    .filter(name -> !hidden(name))
                    .toList());
        }

        private static boolean hidden(String name) {
            return name != null && HIDDEN.contains(name.toLowerCase(Locale.ROOT));
        }
    }
}
