package es.in2.issuanceportal.journey.domain;

/** Why a journey was withdrawn before an offer was obtained. */
public enum WithdrawalReason {

    CANCELLED("cancelled"),
    NOT_ENTITLED("not_entitled"),
    EXPIRED("expired");

    private final String code;

    WithdrawalReason(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static WithdrawalReason fromCode(String code) {
        for (WithdrawalReason reason : values()) {
            if (reason.code.equals(code)) {
                return reason;
            }
        }
        throw new IllegalArgumentException("Unknown withdrawal reason: " + code);
    }
}
