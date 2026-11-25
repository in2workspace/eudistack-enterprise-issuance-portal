package es.in2.issuanceportal.authenticsource.domain;

import java.util.Objects;

/**
 * The holder's identity as the journey verified it, which is all a source needs to find their requests
 * (design.md §3.1). Its text form carries no personal data.
 */
public record Holder(String givenName, String familyNames, PersonalIdentifier identifier) {

    public Holder {
        requireText(givenName, "givenName");
        requireText(familyNames, "familyNames");
        Objects.requireNonNull(identifier, "identifier");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field);
        }
    }

    @Override
    public String toString() {
        return "Holder[" + identifier + ", personal data redacted]";
    }
}
