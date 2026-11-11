package es.in2.issuanceportal.session.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Contract of the {@link Section#ISSUANCE} section (design.md §3.3, §3.4): what the Journey module keeps
 * of the issuance request. The idempotency key is generated first and reused by every retry; the offer
 * and the onboarding address come later, so they are empty until obtained. The offer URI and the
 * onboarding URL are bearer secrets, and the email is personal data: the text form never shows them.
 *
 * @param idempotencyKey    the {@code X-Idempotency-Key} of the journey's single issuance request
 * @param email             the verified email the key was created with; {@code null} in a record written before
 *                          EUD-358
 * @param credentialOfferUri the current offer; {@code null} until obtained
 * @param offerExpiresAt     when the offer expires; {@code null} with no offer
 * @param onboardingUrl      the wallet onboarding address; {@code null} until obtained
 * @param onboardingExpiresAt when the onboarding address expires; {@code null} with no address
 */
public record IssuanceRecord(UUID idempotencyKey, String email, String credentialOfferUri, Instant offerExpiresAt,
                             String onboardingUrl, Instant onboardingExpiresAt) {

    public IssuanceRecord {
        Objects.requireNonNull(idempotencyKey, "idempotency_key");
    }

    /** A record with the key and its email only, before the issuance service has answered. */
    public static IssuanceRecord withKey(UUID idempotencyKey, String email) {
        return new IssuanceRecord(idempotencyKey, email, null, null, null, null);
    }

    /** Whether the key was created with this email: a record without one never was. */
    public boolean isForEmail(String address) {
        return email != null && email.equals(address);
    }

    public Optional<String> offerUri() {
        return Optional.ofNullable(credentialOfferUri);
    }

    public Optional<String> onboarding() {
        return Optional.ofNullable(onboardingUrl);
    }

    public String toJson() {
        return SectionJson.write(new Document(idempotencyKey, email, credentialOfferUri, offerExpiresAt,
                onboardingUrl, onboardingExpiresAt));
    }

    /**
     * Reads the section.
     *
     * @throws InvalidSectionException naming the field that does not have the shape of the contract
     */
    public static IssuanceRecord fromJson(String json) {
        Document document = SectionJson.read(json, Document.class);
        if (document.idempotencyKey() == null) {
            throw new InvalidSectionException("idempotency_key");
        }
        requirePair(document.credentialOfferUri(), document.offerExpiresAt(),
                "credential_offer_uri", "offer_expires_at");
        requirePair(document.onboardingUrl(), document.onboardingExpiresAt(),
                "onboarding_url", "onboarding_expires_at");
        return new IssuanceRecord(document.idempotencyKey(), document.email(), document.credentialOfferUri(),
                document.offerExpiresAt(), document.onboardingUrl(), document.onboardingExpiresAt());
    }

    /** A value and its expiry come together: one without the other is not a record this module writes. */
    private static void requirePair(String value, Instant expiresAt, String valueField, String expiresField) {
        if (value != null && value.isBlank()) {
            throw new InvalidSectionException(valueField);
        }
        if (value == null && expiresAt != null) {
            throw new InvalidSectionException(valueField);
        }
        if (value != null && expiresAt == null) {
            throw new InvalidSectionException(expiresField);
        }
    }

    @Override
    public String toString() {
        return "IssuanceRecord[idempotencyKey=" + idempotencyKey + ", email=" + presence(email) + ", offer=" + presence(credentialOfferUri)
                + ", offerExpiresAt=" + offerExpiresAt + ", onboarding=" + presence(onboardingUrl)
                + ", onboardingExpiresAt=" + onboardingExpiresAt + "]";
    }

    private static String presence(String secret) {
        return secret == null ? "none" : "redacted";
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Document(@JsonProperty("idempotency_key") UUID idempotencyKey,
                    @JsonProperty("email") String email,
                    @JsonProperty("credential_offer_uri") String credentialOfferUri,
                    @JsonProperty("offer_expires_at") Instant offerExpiresAt,
                    @JsonProperty("onboarding_url") String onboardingUrl,
                    @JsonProperty("onboarding_expires_at") Instant onboardingExpiresAt) {

        @Override
        public String toString() {
            return "IssuanceRecord.Document[idempotencyKey=" + idempotencyKey + ", email, offer and onboarding redacted]";
        }
    }
}
