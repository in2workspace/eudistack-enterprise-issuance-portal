package es.in2.issuanceportal.authenticsource.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One request of the holder in the authentic source, with the status the source gives it (design.md §3.1).
 * {@code sourceRef} is the source's own reference: only the adapter and the service use it, it never reaches
 * the interface nor the trace. Its text form carries no personal data.
 *
 * @param mismatchedFields what does not match, when the source says it; empty otherwise
 * @param requestedBy      who asked for the request, as the source gives it; {@code null} if it does not
 * @param requestedAt      when it was made; {@code null} if the source does not give it
 * @param attributes       the request's data by the source's own names
 */
public record SourceRequest(String sourceRef, RequestStatus status, List<MismatchedField> mismatchedFields,
                            boolean renewal, String requestedBy, Instant requestedAt, String displayIdentifier,
                            String organization, Map<String, Object> attributes) {

    public SourceRequest {
        if (sourceRef == null || sourceRef.isBlank()) {
            throw new IllegalArgumentException("sourceRef");
        }
        if (status == null) {
            throw new IllegalArgumentException("status");
        }
        mismatchedFields = mismatchedFields == null ? List.of() : List.copyOf(mismatchedFields);
        attributes = attributes == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    /** The same request with another status: how a request already under review stays so. */
    public SourceRequest withStatus(RequestStatus other) {
        return new SourceRequest(sourceRef, other, mismatchedFields, renewal, requestedBy, requestedAt,
                displayIdentifier, organization, attributes);
    }

    @Override
    public String toString() {
        return "SourceRequest[status=" + status.code() + ", attributes=" + attributes.keySet()
                + ", personal data redacted]";
    }
}
