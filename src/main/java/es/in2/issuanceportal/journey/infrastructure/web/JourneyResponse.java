package es.in2.issuanceportal.journey.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import es.in2.issuanceportal.journey.domain.Journey;
import es.in2.issuanceportal.journey.domain.WithdrawalReason;

/**
 * {@code Journey} of the contract. {@code attestation} and {@code offer} arrive with the stories that
 * write those sections of the session.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record JourneyResponse(
        String state,
        String step,
        @JsonProperty("withdrawal_reason") String withdrawalReason) {

    static JourneyResponse of(Journey journey) {
        return new JourneyResponse(
                journey.state().code(),
                journey.step().code(),
                journey.withdrawalReason().map(WithdrawalReason::code).orElse(null));
    }
}
