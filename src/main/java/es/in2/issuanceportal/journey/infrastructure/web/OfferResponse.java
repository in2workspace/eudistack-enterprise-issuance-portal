package es.in2.issuanceportal.journey.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import es.in2.issuanceportal.journey.application.IssuanceService;

import java.time.Instant;

/**
 * {@code Offer} of the contract. The offer address is a bearer secret: the text form never shows it. The
 * onboarding address and its expiry come with the onboarding ticket (EUD-277 task 7); the issuance process
 * is never part of it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record OfferResponse(
        @JsonProperty("credential_offer_uri") String credentialOfferUri,
        @JsonProperty("expires_at") Instant expiresAt) {

    static OfferResponse of(IssuanceService.Offer offer) {
        return new OfferResponse(offer.credentialOfferUri(), offer.expiresAt());
    }

    @Override
    public String toString() {
        return "OfferResponse[expiresAt=" + expiresAt + ", credentialOfferUri redacted]";
    }
}
