package es.in2.issuanceportal.authenticsource.domain;

import java.util.Locale;
import java.util.Objects;

/** A person's identifier with its type. Its value never appears in logs. */
public record PersonalIdentifier(IdentifierType type, String value) {

    public PersonalIdentifier {
        Objects.requireNonNull(type, "type");
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value");
        }
    }

    /** Same type and same value, ignoring spaces and case: {@code 12345678z} is {@code 12345678Z}. */
    public boolean sameAs(PersonalIdentifier other) {
        return other != null && type == other.type && normalised(value).equals(normalised(other.value));
    }

    private static String normalised(String value) {
        return value.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return "PersonalIdentifier[type=" + type.code() + ", value=redacted]";
    }
}
