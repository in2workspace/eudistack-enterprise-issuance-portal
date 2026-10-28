package es.in2.issuanceportal.session.api;

import java.util.Objects;

/** The holder's personal identifier with its type. Its value never appears in logs. */
public record PersonalIdentifier(IdentifierType type, String value) {

    public PersonalIdentifier {
        Objects.requireNonNull(type, "type");
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value");
        }
    }

    @Override
    public String toString() {
        return "PersonalIdentifier[type=" + type.code() + ", value=redacted]";
    }
}
