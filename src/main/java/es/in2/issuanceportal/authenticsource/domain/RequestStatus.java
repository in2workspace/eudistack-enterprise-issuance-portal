package es.in2.issuanceportal.authenticsource.domain;

import java.util.Arrays;
import java.util.Optional;

/** The state of one request (openapi {@code Request.status}). */
public enum RequestStatus {

    /** The source confirms it and gives its data: the attestation can be requested. */
    AVAILABLE("available"),
    /** The request's identifier does not belong to the holder in the source. */
    IDENTIFIER_MISMATCH("identifier_mismatch"),
    /** The source's data does not match the holder's identification. */
    IDENTITY_MISMATCH("identity_mismatch"),
    /** The holder said its data is not correct in this journey. The source never says it. */
    UNDER_REVIEW("under_review");

    private final String code;

    RequestStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<RequestStatus> fromCode(String code) {
        return Arrays.stream(values()).filter(status -> status.code.equals(code)).findFirst();
    }
}
