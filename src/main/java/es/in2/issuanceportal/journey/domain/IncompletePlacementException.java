package es.in2.issuanceportal.journey.domain;

/**
 * The tenant's data placement cannot fill the attestation: a field points nowhere known or at data the
 * journey does not have. It names the attestation field only, never a value.
 */
public class IncompletePlacementException extends RuntimeException {

    private final transient String field;

    public IncompletePlacementException(String field) {
        super("data_placement cannot fill " + field);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
