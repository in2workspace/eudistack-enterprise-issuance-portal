package es.in2.issuanceportal.authenticsource.domain;

import java.util.Arrays;
import java.util.Optional;

/** Which of the holder's data the source says does not match their identification (openapi {@code mismatched_fields}). */
public enum MismatchedField {

    IDENTIFIER("identifier"),
    GIVEN_NAME("given_name"),
    FAMILY_NAMES("family_names");

    private final String code;

    MismatchedField(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<MismatchedField> fromCode(String code) {
        return Arrays.stream(values()).filter(field -> field.code.equals(code)).findFirst();
    }
}
