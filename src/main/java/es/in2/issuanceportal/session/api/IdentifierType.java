package es.in2.issuanceportal.session.api;

import java.util.Arrays;
import java.util.Optional;

/**
 * What the holder's personal identifier is, set by the Journey module from the login method
 * (design.md §3.1). The codes are the ones of the authentic source port.
 */
public enum IdentifierType {

    /** DNI or NIE: login with a qualified certificate (and Cl@ve in the future). */
    DNI("dni"),
    /** Medical registration number: login with DoctorID. */
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
