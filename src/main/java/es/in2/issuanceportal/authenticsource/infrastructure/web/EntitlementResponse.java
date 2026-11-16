package es.in2.issuanceportal.authenticsource.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import es.in2.issuanceportal.authenticsource.application.EntitlementService;
import es.in2.issuanceportal.authenticsource.domain.AttestationPreview;
import es.in2.issuanceportal.authenticsource.domain.MismatchedField;
import es.in2.issuanceportal.authenticsource.domain.NotConfirmedReason;

import java.util.List;

/**
 * {@code EntitlementResult} of the contract (annex/openapi.yaml, version 2). It never carries the source's
 * reference of a request. Its text form carries no personal data: Spring writes it to the log at DEBUG.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record EntitlementResponse(String result, String reason, List<Request> requests) {

    static EntitlementResponse of(EntitlementService.Outcome outcome) {
        List<Request> requests = outcome.requests().stream().map(Request::of).toList();
        return switch (outcome) {
            case EntitlementService.Outcome.Confirmed _ -> new EntitlementResponse("confirmed", null, requests);
            case EntitlementService.Outcome.NotConfirmed(var reason, var _) ->
                    new EntitlementResponse("not_confirmed", reason.map(NotConfirmedReason::code).orElse(null),
                            requests);
        };
    }

    @Override
    public String toString() {
        return "EntitlementResponse[result=" + result + ", reason=" + reason + ", requests=" + requests.size() + "]";
    }

    /** {@code Request} of the contract. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Request(String id,
                   String status,
                   @JsonProperty("mismatched_fields") @JsonInclude(JsonInclude.Include.NON_EMPTY)
                   List<String> mismatchedFields,
                   boolean renewal,
                   @JsonProperty("requested_by") String requestedBy,
                   @JsonProperty("requested_at") String requestedAt,
                   @JsonProperty("display_identifier") String displayIdentifier,
                   String organization,
                   Attestation attestation) {

        static Request of(EntitlementService.CheckedRequest request) {
            return new Request(request.id(), request.status().code(),
                    request.mismatchedFields().stream().map(MismatchedField::code).toList(), request.renewal(),
                    request.requestedBy(), request.requestedAt() == null ? null : request.requestedAt().toString(),
                    request.displayIdentifier(), request.organization(),
                    request.preview().map(Attestation::of).orElse(null));
        }

        @Override
        public String toString() {
            return "Request[id=" + id + ", status=" + status + ", personal data redacted]";
        }
    }

    /** {@code AttestationPreview} of the contract. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Attestation(@JsonProperty("type_name") String typeName, List<Field> fields) {

        static Attestation of(AttestationPreview preview) {
            return new Attestation(preview.typeName(),
                    preview.fields().stream().map(field -> new Field(field.label(), field.value())).toList());
        }

        @Override
        public String toString() {
            return "Attestation[typeName=" + typeName + ", fields=" + fields.size() + ", personal data redacted]";
        }
    }

    record Field(String label, String value) {

        @Override
        public String toString() {
            return "Field[label=" + label + ", value=redacted]";
        }
    }
}
