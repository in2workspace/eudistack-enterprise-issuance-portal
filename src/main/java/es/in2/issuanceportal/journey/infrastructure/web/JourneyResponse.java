package es.in2.issuanceportal.journey.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import es.in2.issuanceportal.journey.application.JourneyViewService;
import es.in2.issuanceportal.journey.domain.Journey;
import es.in2.issuanceportal.journey.domain.WithdrawalReason;
import es.in2.issuanceportal.session.api.EntitlementRecord;

import java.util.List;

/**
 * {@code Journey} of the contract. {@code GET /journey} adds {@code attestation} and {@code offer} when the
 * session has them (EUD-277 decision 8); opening and withdrawing the journey answer without them.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record JourneyResponse(
        String state,
        String step,
        @JsonProperty("withdrawal_reason") String withdrawalReason,
        AttestationPreviewResponse attestation,
        OfferResponse offer) {

    static JourneyResponse of(Journey journey) {
        return new JourneyResponse(
                journey.state().code(),
                journey.step().code(),
                journey.withdrawalReason().map(WithdrawalReason::code).orElse(null),
                null,
                null);
    }

    static JourneyResponse of(JourneyViewService.View view) {
        JourneyResponse base = of(view.journey());
        return new JourneyResponse(base.state(), base.step(), base.withdrawalReason(),
                view.attestation().map(AttestationPreviewResponse::of).orElse(null),
                view.offer().map(OfferResponse::of).orElse(null));
    }

    @Override
    public String toString() {
        return "JourneyResponse[state=" + state + ", step=" + step + ", withdrawalReason=" + withdrawalReason
                + ", attestation=" + (attestation != null) + ", offer=" + (offer != null) + "]";
    }

    /** {@code AttestationPreview} of the contract: what the Data step shows. Personal data: never in the text form. */
    record AttestationPreviewResponse(
            @JsonProperty("type_name") String typeName,
            @JsonProperty("fields") List<FieldResponse> fields,
            @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("email") String email,
            @JsonProperty("email_required") boolean emailRequired) {

        static AttestationPreviewResponse of(EntitlementRecord.Preview preview) {
            return new AttestationPreviewResponse(preview.typeName(),
                    preview.fields().stream().map(field -> new FieldResponse(field.label(), field.value())).toList(),
                    preview.email(), preview.emailRequired());
        }

        @Override
        public String toString() {
            return "AttestationPreviewResponse[typeName=" + typeName + ", fields=" + fields.size()
                    + ", emailRequired=" + emailRequired + ", personal data redacted]";
        }
    }

    record FieldResponse(@JsonProperty("label") String label, @JsonProperty("value") String value) {

        @Override
        public String toString() {
            return "FieldResponse[label=" + label + ", value redacted]";
        }
    }
}
