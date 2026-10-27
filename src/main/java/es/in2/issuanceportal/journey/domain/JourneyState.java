package es.in2.issuanceportal.journey.domain;

/** Where a journey is in its lifecycle. {@code completed} and {@code withdrawn} are final. */
public enum JourneyState {

    IN_PROGRESS("in_progress"),
    COMPLETED("completed"),
    WITHDRAWN("withdrawn");

    private final String code;

    JourneyState(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static JourneyState fromCode(String code) {
        for (JourneyState state : values()) {
            if (state.code.equals(code)) {
                return state;
            }
        }
        throw new IllegalArgumentException("Unknown journey state: " + code);
    }
}
