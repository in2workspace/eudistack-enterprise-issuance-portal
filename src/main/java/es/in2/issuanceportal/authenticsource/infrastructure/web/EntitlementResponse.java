package es.in2.issuanceportal.authenticsource.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import es.in2.issuanceportal.authenticsource.application.EntitlementService;
import es.in2.issuanceportal.authenticsource.domain.AttestationPreview;

import java.util.List;

/**
 * {@code EntitlementResult} of the contract (annex/openapi.yaml). Its text form carries no personal data:
 * Spring writes it to the log at DEBUG.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record EntitlementResponse(String result, Attestation attestation, String reason) {

    static EntitlementResponse of(EntitlementService.Outcome outcome) {
        return switch (outcome) {
            case EntitlementService.Outcome.Confirmed(AttestationPreview preview) ->
                    new EntitlementResponse("confirmed", Attestation.of(preview), null);
            case EntitlementService.Outcome.NotConfirmed notConfirmed ->
                    new EntitlementResponse("not_confirmed", null, notConfirmed.reason().code());
        };
    }

    @Override
    public String toString() {
        return "EntitlementResponse[result=" + result + ", reason=" + reason + ", attestation=" + attestation + "]";
    }

    /** {@code AttestationPreview} of the contract. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Attestation(@JsonProperty("type_name") String typeName,
                       List<Field> fields,
                       String email,
                       @JsonProperty("email_required") boolean emailRequired) {

        static Attestation of(AttestationPreview preview) {
            return new Attestation(preview.typeName(),
                    preview.fields().stream().map(field -> new Field(field.label(), field.value())).toList(),
                    preview.email(), preview.emailRequired());
        }

        @Override
        public String toString() {
            return "Attestation[typeName=" + typeName + ", fields=" + fields.size() + ", emailRequired="
                    + emailRequired + ", personal data redacted]";
        }
    }

    record Field(String label, String value) {

        @Override
        public String toString() {
            return "Field[label=" + label + ", value=redacted]";
        }
    }
}
