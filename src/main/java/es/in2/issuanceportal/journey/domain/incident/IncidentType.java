package es.in2.issuanceportal.journey.domain.incident;

/** Kind of incident the holder reports (interface prd.md §3.2), with the label the support team reads. */
public enum IncidentType {

    TECHNICAL("technical", "Problema técnico"),
    ACCESS("access", "Problema de acceso"),
    CREDENTIALS("credentials", "Gestión de credenciales"),
    OTHER("other", "Otro");

    private final String code;
    private final String label;

    IncidentType(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static IncidentType fromCode(String code) {
        for (IncidentType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown incident type: " + code);
    }
}
