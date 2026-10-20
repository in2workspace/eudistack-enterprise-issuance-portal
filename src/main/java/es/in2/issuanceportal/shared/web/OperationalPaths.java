package es.in2.issuanceportal.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UrlPathHelper;

/**
 * The only paths the security filters leave alone: health and metrics. Everything else is treated as
 * API, so a new endpoint is protected by default. The check uses the path as Spring MVC matches it
 * (decoded, without {@code ;params}), so {@code %61pi} or {@code api;x} cannot slip past the filters
 * and still reach a controller.
 */
public final class OperationalPaths {

    private static final String ACTUATOR = "/actuator";
    private static final UrlPathHelper PATHS = new UrlPathHelper();

    static {
        PATHS.setUrlDecode(true);
        PATHS.setRemoveSemicolonContent(true);
    }

    private OperationalPaths() {
    }

    public static boolean isOperational(HttpServletRequest request) {
        String path = PATHS.getPathWithinApplication(request);
        // "/actuator/../api/..." must not count as operational, whatever the router later does with it.
        if (!path.equals(StringUtils.cleanPath(path))) {
            return false;
        }
        return path.equals(ACTUATOR) || path.startsWith(ACTUATOR + "/");
    }
}
