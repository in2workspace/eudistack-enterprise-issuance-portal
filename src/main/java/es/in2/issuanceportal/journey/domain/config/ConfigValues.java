package es.in2.issuanceportal.journey.domain.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Format checks shared by the parts of the portal configuration. */
final class ConfigValues {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Set<String> WEB_SCHEMES = Set.of("http", "https");

    private ConfigValues() {
    }

    static <T> T required(T value, String field) {
        if (value == null) {
            throw new InvalidPortalConfigException(field + " is required");
        }
        return value;
    }

    /** An absolute URL with a host and one of the given schemes. Null passes: presence is checked apart. */
    static void requireUrl(String value, String field, Set<String> schemes) {
        if (value == null) {
            return;
        }
        try {
            URI uri = new URI(value);
            if (!uri.isAbsolute() || uri.getHost() == null || !schemes.contains(uri.getScheme().toLowerCase(Locale.ROOT))) {
                throw new InvalidPortalConfigException(field + " is not a valid " + String.join(" or ", schemes) + " URL");
            }
        } catch (URISyntaxException e) {
            throw new InvalidPortalConfigException(field + " is not a valid URL");
        }
    }

    static void requireWebUrl(String value, String field) {
        requireUrl(value, field, WEB_SCHEMES);
    }

    static void requireEmail(String value, String field) {
        if (value != null && !EMAIL.matcher(value).matches()) {
            throw new InvalidPortalConfigException(field + " is not an email address");
        }
    }

    /** Immutable copy that, unlike {@link Map#copyOf}, accepts the null values a JSON object may hold. */
    static <V> Map<String, V> copy(Map<String, V> map) {
        return map == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }
}
