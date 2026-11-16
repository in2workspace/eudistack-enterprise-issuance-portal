package es.in2.issuanceportal.authenticsource.domain;

import java.util.Arrays;
import java.util.Optional;

/** What a personal identifier is, and so how the source is searched (design.md §3.1). */
public enum IdentifierType {

    /** The holder's personal identifier (DNI, NIE or the like), the one the login gives. */
    PERSONAL_ID("personal_id"),
    /** Medical registration number. */
    COLEGIADO("colegiado");

    private final String code;

    IdentifierType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<IdentifierType> fromCode(String code) {
        return Arrays.stream(values()).filter(type -> type.code.equals(code)).findFirst();
    }
}
