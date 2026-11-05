package es.in2.issuanceportal.authenticsource.domain;

/** What a personal identifier is, and so how the source is searched (design.md §3.1). */
public enum IdentifierType {

    /** DNI or NIE. */
    DNI("dni"),
    /** Medical registration number. */
    COLEGIADO("colegiado");

    private final String code;

    IdentifierType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
