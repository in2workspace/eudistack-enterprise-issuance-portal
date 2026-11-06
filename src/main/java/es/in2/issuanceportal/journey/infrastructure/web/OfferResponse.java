package es.in2.issuanceportal.journey.infrastructure.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import es.in2.issuanceportal.journey.application.IssuanceService;

import java.time.Instant;

/**
 * {@code Offer} of the contract. The offer address and the onboarding address are bearer secrets: the text form
 * never shows them. The onboarding fields are only present in a tenant with onboarding tickets; the issuance
 * process is never part of it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record OfferResponse(
        @JsonProperty("credential_offer_uri") String credentialOfferUri,
        @JsonProperty("expires_at") Instant expiresAt,
        @JsonProperty("onboarding_url") String onboardingUrl,
        @JsonProperty("onboarding_expires_at") Instant onboardingExpiresAt) {

    static OfferResponse of(IssuanceService.Offer offer) {
        return new OfferResponse(offer.credentialOfferUri(), offer.expiresAt(), offer.onboardingUrl(),
                offer.onboardingExpiresAt());
    }

    @Override
    public String toString() {
        return "OfferResponse[expiresAt=" + expiresAt + ", onboardingExpiresAt=" + onboardingExpiresAt
                + ", credentialOfferUri and onboardingUrl redacted]";
    }
}
