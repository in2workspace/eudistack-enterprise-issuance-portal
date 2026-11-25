package es.in2.issuanceportal.journey.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import es.in2.issuanceportal.journey.application.JourneyViewService;
import es.in2.issuanceportal.journey.domain.Journey;
import es.in2.issuanceportal.journey.domain.WithdrawalReason;

import java.util.List;

/**
 * {@code Journey} of the contract (annex/openapi.yaml, version 3). {@code GET /journey} adds the known email, the email
 * verification and the requests when the session has them; opening and withdrawing the journey answer without them.
 * The requests carry only what the contract lists: never the source's reference, the identity or the identifier
 * the holder sees in the authentic source.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record JourneyResponse(
        String state,
        String step,
        @JsonProperty("withdrawal_reason") String withdrawalReason,
        @JsonProperty("known_email") String knownEmail,
        @JsonProperty("email_verification") EmailVerificationResponse emailVerification,
        List<RequestResponse> requests) {

    static JourneyResponse of(Journey journey) {
        return new JourneyResponse(
                journey.state().code(),
                journey.step().code(),
                journey.withdrawalReason().map(WithdrawalReason::code).orElse(null),
                null,
                null,
                null);
    }

    static JourneyResponse of(JourneyViewService.View view) {
        JourneyResponse base = of(view.journey());
        return new JourneyResponse(base.state, base.step, base.withdrawalReason,
                view.knownEmail().orElse(null),
                view.emailVerification().map(EmailVerificationResponse::of).orElse(null),
                view.requests().isEmpty() ? null : view.requests().stream().map(RequestResponse::of).toList());
    }

    /** Personal data: never in the text form. */
    @Override
    public String toString() {
        return "JourneyResponse[state=" + state + ", step=" + step + ", withdrawalReason=" + withdrawalReason
                + ", requests=" + (requests == null ? 0 : requests.size()) + ", personal data redacted]";
    }

    /** {@code JourneyRequest} of the contract. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record RequestResponse(
            String id,
            String status,
            @JsonProperty("not_available_reason") String notAvailableReason,
            @JsonProperty("requested_by") String requestedBy,
            @JsonProperty("requested_at") String requestedAt,
            boolean renewal,
            @JsonProperty("display_identifier") String displayIdentifier,
            String organization,
            AttestationPreviewResponse attestation,
            OfferResponse offer) {

        static RequestResponse of(JourneyViewService.RequestView request) {
            return new RequestResponse(request.id(), request.status().code(),
                    request.notAvailableReason().orElse(null), request.requestedBy().orElse(null),
                    request.requestedAt().map(Object::toString).orElse(null), request.renewal(),
                    request.displayIdentifier().orElse(null), request.organization().orElse(null),
                    request.attestation().map(AttestationPreviewResponse::of).orElse(null),
                    request.offer().map(OfferResponse::of).orElse(null));
        }

        @Override
        public String toString() {
            return "RequestResponse[id=" + id + ", status=" + status + ", personal data and offer redacted]";
        }
    }

    /** {@code AttestationPreview} of the contract: what the Data step shows. Personal data: never in the text form. */
    record AttestationPreviewResponse(
            @JsonProperty("type_name") String typeName,
            @JsonProperty("fields") List<FieldResponse> fields) {

        static AttestationPreviewResponse of(JourneyViewService.Attestation preview) {
            return new AttestationPreviewResponse(preview.typeName(),
                    preview.fields().stream().map(field -> new FieldResponse(field.label(), field.value())).toList());
        }

        @Override
        public String toString() {
            return "AttestationPreviewResponse[typeName=" + typeName + ", fields=" + fields.size()
                    + ", personal data redacted]";
        }
    }

    record FieldResponse(@JsonProperty("label") String label, @JsonProperty("value") String value) {

        @Override
        public String toString() {
            return "FieldResponse[label=" + label + ", value redacted]";
        }
    }
}
