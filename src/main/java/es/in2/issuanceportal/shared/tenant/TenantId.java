package es.in2.issuanceportal.shared.tenant;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A tenant identifier, valid by construction: it is safe to use as part of a SQL schema name.
 */
public record TenantId(String value) {

    private static final Pattern VALID = Pattern.compile("^[a-z0-9_-]+$");
    /** Suffix of every tenant schema of this service: {@code <tenant>_issuance_portal}. */
    public static final String SCHEMA_SUFFIX = "_issuance_portal";

    public TenantId {
        if (value == null || !VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid tenant identifier");
        }
    }

    /** Normalises a candidate to lowercase and returns it only when it is a valid identifier. */
    public static Optional<TenantId> parse(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return Optional.empty();
        }
        String normalised = candidate.trim().toLowerCase(Locale.ROOT);
        return VALID.matcher(normalised).matches() ? Optional.of(new TenantId(normalised)) : Optional.empty();
    }

    /** The tenant's schema for this service, quoted so a hyphen in the identifier stays valid SQL. */
    public String schema() {
        return "\"" + schemaName() + "\"";
    }

    /** The tenant's schema for this service, unquoted, for tools that quote it themselves. */
    public String schemaName() {
        return value + SCHEMA_SUFFIX;
    }

    @Override
    public String toString() {
        return value;
    }
}
