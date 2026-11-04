package es.in2.issuanceportal.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UrlPathHelper;

/**
 * The only paths the security filters leave alone: health and metrics. Everything else is treated as
 * API, so a new endpoint is protected by default. The check uses the path as Spring MVC matches it
 * (decoded, without {@code ;params}), so {@code %61pi} or {@code api;x} cannot slip past the filters
 * and still reach a controller.
 */
public final class OperationalPaths {

    // Actuator endpoints at the root of the service (management.endpoints.web.base-path: /), as in the
    // other platform services: /issuance-portal/health, /issuance-portal/prometheus.
    private static final String HEALTH = "/health";
    private static final Set<String> OTHER_ENDPOINTS = Set.of("/info", "/prometheus");
    private static final UrlPathHelper PATHS = new UrlPathHelper();

    static {
        PATHS.setUrlDecode(true);
        PATHS.setRemoveSemicolonContent(true);
    }

    private OperationalPaths() {
    }

    public static boolean isOperational(HttpServletRequest request) {
        String path = PATHS.getPathWithinApplication(request);
        // "/health/../api/..." must not count as operational, whatever the router later does with it.
        if (!path.equals(StringUtils.cleanPath(path))) {
            return false;
        }
        return path.equals(HEALTH) || path.startsWith(HEALTH + "/") || OTHER_ENDPOINTS.contains(path);
    }
}
