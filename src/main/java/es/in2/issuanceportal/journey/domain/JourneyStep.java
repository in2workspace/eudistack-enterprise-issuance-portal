package es.in2.issuanceportal.journey.domain;

/** The last step a journey reached, in the order the holder goes through them. */
public enum JourneyStep {

    STARTED("started"),
    IDENTIFIED("identified"),
    ENTITLEMENT_CONFIRMED("entitlement_confirmed"),
    OFFER_OBTAINED("offer_obtained");

    private final String code;

    JourneyStep(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static JourneyStep fromCode(String code) {
        for (JourneyStep step : values()) {
            if (step.code.equals(code)) {
                return step;
            }
        }
        throw new IllegalArgumentException("Unknown journey step: " + code);
    }
}
