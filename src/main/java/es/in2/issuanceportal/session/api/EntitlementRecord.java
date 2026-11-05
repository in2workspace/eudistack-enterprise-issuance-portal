package es.in2.issuanceportal.session.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Contract of the {@link Section#ENTITLEMENT} section: a confirmed entitlement check. The Authentic
 * source module writes it; the Journey module reads it to request the issuance. A check that is not
 * confirmed withdraws the journey, so it is never written. Its text form carries no personal data.
 *
 * @param identifierType the type of identifier the source was searched by; {@code null} when the
 *                       attestation type has no source
 * @param claims         the attestation data translated from the source, by claim name; empty when the
 *                       attestation type has no source
 * @param preview        what the Data step shows
 */
public record EntitlementRecord(IdentifierType identifierType, Map<String, Object> claims, Preview preview,
                                Instant checkedAt) {

    private static final String RESULT_CONFIRMED = "confirmed";

    public EntitlementRecord {
        claims = claims == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(claims));
        Objects.requireNonNull(preview, "preview");
        Objects.requireNonNull(checkedAt, "checked_at");
    }

    public Optional<IdentifierType> identifierTypeIfSearched() {
        return Optional.ofNullable(identifierType);
    }

    public String toJson() {
        return SectionJson.write(new Document(RESULT_CONFIRMED,
                identifierType == null ? null : identifierType.code(), claims, preview, checkedAt));
    }

    /**
     * Reads the section.
     *
     * @throws InvalidSectionException naming the field that does not have the shape of the contract
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
        if (document.preview() == null) {
            throw new InvalidSectionException("preview");
        }
        if (document.checkedAt() == null) {
            throw new InvalidSectionException("checked_at");
        }
        return new EntitlementRecord(type, document.claims(), document.preview(), document.checkedAt());
    }

    private static final String REDACTED = ", personal data redacted]";

    @Override
    public String toString() {
        return "EntitlementRecord[identifierType=" + (identifierType == null ? "none" : identifierType.code())
                + ", claims=" + claims.keySet() + ", checkedAt=" + checkedAt + REDACTED;
    }

    /** What the interface shows in the Data step (openapi AttestationPreview). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Preview(@JsonProperty("type_name") String typeName,
                          @JsonProperty("fields") List<Field> fields,
                          @JsonProperty("email") String email,
                          @JsonProperty("email_required") boolean emailRequired) {

        public Preview {
            fields = fields == null ? List.of() : List.copyOf(fields);
        }

        @Override
        public String toString() {
            return "Preview[typeName=" + typeName + ", fields=" + fields.size() + ", emailRequired="
                    + emailRequired + REDACTED;
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
                    @JsonProperty("claims") Map<String, Object> claims,
                    @JsonProperty("preview") Preview preview,
                    @JsonProperty("checked_at") Instant checkedAt) {

        @Override
        public String toString() {
            return "EntitlementRecord.Document[result=" + result + REDACTED;
        }
    }
}
