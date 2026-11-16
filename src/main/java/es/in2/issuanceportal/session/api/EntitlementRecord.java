package es.in2.issuanceportal.session.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Contract of the {@link Section#ENTITLEMENT} section: a confirmed entitlement check, with one entry per
 * request of the holder (design.md AD-6 of the Authentic source module). The Authentic source module writes
 * it; the Journey module reads it to request the issuance. A check that is not confirmed withdraws the
 * journey, so it is never written. The source's own reference of each request never leaves it. Its text
 * form carries no personal data.
 *
 * @param identifierType the type of identifier the source was searched by; {@code null} when the
 *                       attestation type has no source
 * @param email          the email the holder is known by (Access Token or source), to prefill the Email step;
 *                       {@code null} if none
 * @param requests       the holder's requests in the order the source gave them; at least one is available
 */
public record EntitlementRecord(IdentifierType identifierType, String email, List<Request> requests,
                                Instant checkedAt) {

    private static final String RESULT_CONFIRMED = "confirmed";
    /** The longest id of a request that the journey contract admits (openapi, {@code request_id}). */
    private static final int MAX_ID_LENGTH = 64;

    public EntitlementRecord {
        email = email == null || email.isBlank() ? null : email;
        requests = requests == null ? List.of() : List.copyOf(requests);
        Objects.requireNonNull(checkedAt, "checked_at");
    }

    public Optional<IdentifierType> identifierTypeIfSearched() {
        return Optional.ofNullable(identifierType);
    }

    public Optional<String> emailIfKnown() {
        return Optional.ofNullable(email);
    }

    /** The request with this id, if the check has it. */
    public Optional<Request> request(String id) {
        return requests.stream().filter(request -> request.id().equals(id)).findFirst();
    }

    /** The first request the attestation can be requested for. */
    public Optional<Request> firstAvailable() {
        return requests.stream().filter(request -> request.status() == Status.AVAILABLE).findFirst();
    }

    public String toJson() {
        return SectionJson.write(new Document(RESULT_CONFIRMED,
                identifierType == null ? null : identifierType.code(), email,
                requests.stream().map(Request::toDocument).toList(), checkedAt));
    }

    /**
     * Reads the section.
     *
     * @throws InvalidSectionException naming the field that does not have the shape of the contract. A
     *                                 section written before the list of requests has no {@code requests}
     */
    public static EntitlementRecord fromJson(String json) {
        Document document = SectionJson.read(json, Document.class);
        if (!RESULT_CONFIRMED.equals(document.result())) {
            throw new InvalidSectionException("result");
        }
        IdentifierType type = null;
        if (document.identifierType() != null) {
            type = IdentifierType.fromCode(document.identifierType())
                    .orElseThrow(() -> new InvalidSectionException("identifier_type"));
        }
        if (document.checkedAt() == null) {
            throw new InvalidSectionException("checked_at");
        }
        if (document.requests() == null || document.requests().isEmpty()) {
            throw new InvalidSectionException("requests");
        }
        List<Request> requests = new ArrayList<>();
        for (int i = 0; i < document.requests().size(); i++) {
            requests.add(Request.fromDocument(document.requests().get(i), i));
        }
        return new EntitlementRecord(type, document.email(), requests, document.checkedAt());
    }

    private static final String REDACTED = ", personal data redacted]";

    @Override
    public String toString() {
        return "EntitlementRecord[identifierType=" + (identifierType == null ? "none" : identifierType.code())
                + ", requests=" + requests.size() + ", checkedAt=" + checkedAt + REDACTED;
    }

    /** The state of a request (openapi {@code Request.status}). */
    public enum Status {

        AVAILABLE("available"),
        IDENTIFIER_MISMATCH("identifier_mismatch"),
        IDENTITY_MISMATCH("identity_mismatch"),
        UNDER_REVIEW("under_review");

        private final String code;

        Status(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public static Optional<Status> fromCode(String code) {
            return Arrays.stream(values()).filter(status -> status.code.equals(code)).findFirst();
        }

        /** Whether the request has data to show and to issue: only these two carry claims and a preview. */
        public boolean carriesData() {
            return this == AVAILABLE || this == UNDER_REVIEW;
        }
    }

    /**
     * One request of the holder.
     *
     * @param id        opaque, assigned by the Authentic source module, stable within the journey and at
     *                  most 64 characters
     * @param sourceRef the source's own reference. Never leaves the service nor reaches a log
     * @param claims    the attestation data placed from the source, by claim name; empty unless the
     *                  request carries data
     * @param preview   what the Data step shows; {@code null} unless the request carries data
     */
    public record Request(String id, String sourceRef, Status status, List<String> mismatchedFields,
                          boolean renewal, String requestedBy, Instant requestedAt, String displayIdentifier,
                          String organization, Map<String, Object> claims, Preview preview) {

        public Request {
            mismatchedFields = mismatchedFields == null ? List.of() : List.copyOf(mismatchedFields);
            claims = claims == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(claims));
        }

        private RequestDocument toDocument() {
            return new RequestDocument(id, sourceRef, status.code(), mismatchedFields.isEmpty() ? null : mismatchedFields,
                    renewal, requestedBy, requestedAt, displayIdentifier, organization,
                    claims.isEmpty() ? null : claims, preview);
        }

        private static Request fromDocument(RequestDocument document, int index) {
            String path = "requests[" + index + "].";
            if (document == null || document.id() == null || document.id().isBlank()
                    || document.id().length() > MAX_ID_LENGTH) {
                throw new InvalidSectionException(path + "id");
            }
            if (document.sourceRef() == null || document.sourceRef().isBlank()) {
                throw new InvalidSectionException(path + "source_ref");
            }
            Status status = Status.fromCode(document.status())
                    .orElseThrow(() -> new InvalidSectionException(path + "status"));
            if (status.carriesData() && document.preview() == null) {
                throw new InvalidSectionException(path + "preview");
            }
            return new Request(document.id(), document.sourceRef(), status, document.mismatchedFields(),
                    document.renewal(), document.requestedBy(), document.requestedAt(),
                    document.displayIdentifier(), document.organization(), document.claims(),
                    document.preview());
        }

        @Override
        public String toString() {
            return "Request[id=" + id + ", status=" + status.code() + ", claims=" + claims.keySet()
                    + REDACTED;
        }
    }

    /** What the interface shows in the Data step of a request (openapi AttestationPreview). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Preview(@JsonProperty("type_name") String typeName,
                          @JsonProperty("fields") List<Field> fields) {

        public Preview {
            fields = fields == null ? List.of() : List.copyOf(fields);
        }

        @Override
        public String toString() {
            return "Preview[typeName=" + typeName + ", fields=" + fields.size() + REDACTED;
        }
    }

    /** A labelled value of the preview. */
    public record Field(@JsonProperty("label") String label, @JsonProperty("value") String value) {

        @Override
        public String toString() {
            return "Field[label=" + label + ", value=redacted]";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Document(@JsonProperty("result") String result,
                    @JsonProperty("identifier_type") String identifierType,
                    @JsonProperty("email") String email,
                    @JsonProperty("requests") List<RequestDocument> requests,
                    @JsonProperty("checked_at") Instant checkedAt) {

        @Override
        public String toString() {
            return "EntitlementRecord.Document[result=" + result + REDACTED;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RequestDocument(@JsonProperty("id") String id,
                           @JsonProperty("source_ref") String sourceRef,
                           @JsonProperty("status") String status,
                           @JsonProperty("mismatched_fields") List<String> mismatchedFields,
                           @JsonProperty("renewal") boolean renewal,
                           @JsonProperty("requested_by") String requestedBy,
                           @JsonProperty("requested_at") Instant requestedAt,
                           @JsonProperty("display_identifier") String displayIdentifier,
                           @JsonProperty("organization") String organization,
                           @JsonProperty("claims") Map<String, Object> claims,
                           @JsonProperty("preview") Preview preview) {

        @Override
        public String toString() {
            return "EntitlementRecord.RequestDocument[id=" + id + REDACTED;
        }
    }
}
