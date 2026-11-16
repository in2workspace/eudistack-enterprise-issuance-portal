package es.in2.issuanceportal.journey.infrastructure.web;

import com.fasterxml.jackson.databind.JsonNode;
import es.in2.issuanceportal.shared.error.ProblemCode;
import es.in2.issuanceportal.shared.error.ProblemException;

/**
 * {@code RequestReference} of the contract: the body of the operations that act on one request. Read as a tree so a
 * number is not taken as its text.
 */
final class RequestReferences {

    private static final String REQUEST_ID = "request_id";

    private RequestReferences() {
    }

    /** @throws ProblemException {@code bad_request} for a missing body or a {@code request_id} that is not a string */
    static String requestIdOf(JsonNode body) {
        JsonNode requestId = body == null ? null : body.get(REQUEST_ID);
        if (requestId == null || !requestId.isTextual()) {
            throw new ProblemException(ProblemCode.BAD_REQUEST);
        }
        return requestId.asText();
    }
}
