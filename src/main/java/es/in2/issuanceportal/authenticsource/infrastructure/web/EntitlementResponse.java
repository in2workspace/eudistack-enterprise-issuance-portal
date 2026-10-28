package es.in2.issuanceportal.authenticsource.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import es.in2.issuanceportal.authenticsource.application.EntitlementService;
import es.in2.issuanceportal.authenticsource.domain.AttestationPreview;

import java.util.List;

/** {@code EntitlementResult} of the contract (annex/openapi.yaml). */
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
    }

    record Field(String label, String value) {
    }
}
