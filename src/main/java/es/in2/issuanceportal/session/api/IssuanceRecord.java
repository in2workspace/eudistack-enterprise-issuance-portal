package es.in2.issuanceportal.session.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Contract of the {@link Section#ISSUANCE} section (design.md §3.3, §3.4, §3.7): what the Journey module keeps of
 * the issuance of each request, one entry per request id (the opaque id the Authentic source module gave it). The
 * idempotency key of an entry is generated first and reused by every retry; the offer and the onboarding address
 * come later, so they are empty until obtained. The offer URI and the onboarding URL are bearer secrets, and the
 * email is personal data: the text form never shows them.
 *
 * <p>A section written before there was one entry per request (a single key at the top) is not readable: the journeys
 * that still have one end with {@code step_not_allowed}, as with their entitlement section.
 */
public record IssuanceRecord(Map<String, Entry> requests) {

    /** The longest request id the journey contract admits. */
    private static final int MAX_ID_LENGTH = 64;

    public IssuanceRecord {
        requests = requests == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(requests));
    }

    /** A section without any request yet. */
    public static IssuanceRecord empty() {
        return new IssuanceRecord(Map.of());
    }

    /** The entry of the request, if its issuance was started. */
    public Optional<Entry> entry(String requestId) {
        return Optional.ofNullable(requests.get(requestId));
    }

    /** The same section with the entry of the request replaced, or added after the others. */
    public IssuanceRecord with(String requestId, Entry entry) {
        Map<String, Entry> changed = new LinkedHashMap<>(requests);
        changed.put(requestId, entry);
        return new IssuanceRecord(changed);
    }

    public String toJson() {
        Map<String, EntryDocument> documents = new LinkedHashMap<>();
        requests.forEach((id, entry) -> documents.put(id, entry.toDocument()));
        return SectionJson.write(new Document(documents));
    }

    /**
     * Reads the section.
     *
     * @throws InvalidSectionException naming the field that does not have the shape of the contract
     */
    public static IssuanceRecord fromJson(String json) {
        Document document = SectionJson.read(json, Document.class);
        if (document.requests() == null) {
            throw new InvalidSectionException("requests");
        }
        Map<String, Entry> requests = new LinkedHashMap<>();
        document.requests().forEach((id, entry) -> {
            if (id == null || id.isBlank() || id.length() > MAX_ID_LENGTH) {
                throw new InvalidSectionException("requests");
            }
            requests.put(id, Entry.fromDocument(entry, "requests." + id + "."));
        });
        return new IssuanceRecord(requests);
    }

    @Override
    public String toString() {
        return "IssuanceRecord[requests=" + requests.keySet() + ", offers and addresses redacted]";
    }

    /**
     * The issuance of one request.
     *
     * @param idempotencyKey     the {@code X-Idempotency-Key} of the request's single issuance request
     * @param email              the verified email the key was created with
     * @param credentialOfferUri the current offer; {@code null} until obtained
     * @param offerExpiresAt     when the offer expires; {@code null} with no offer
     * @param onboardingUrl      the wallet onboarding address; {@code null} until obtained
     * @param onboardingExpiresAt when the onboarding address expires; {@code null} with no address
     */
    public record Entry(UUID idempotencyKey, String email, String credentialOfferUri, Instant offerExpiresAt,
                        String onboardingUrl, Instant onboardingExpiresAt) {

        public Entry {
            Objects.requireNonNull(idempotencyKey, "idempotency_key");
        }

        /** An entry with the key and its email only, before the issuance service has answered. */
        public static Entry withKey(UUID idempotencyKey, String email) {
            return new Entry(idempotencyKey, email, null, null, null, null);
        }

        /** Whether the key was created with this email: an entry without one never was. */
        public boolean isForEmail(String address) {
            return email != null && email.equals(address);
        }

        public Optional<String> offerUri() {
            return Optional.ofNullable(credentialOfferUri);
        }

        public Optional<String> onboarding() {
            return Optional.ofNullable(onboardingUrl);
        }

        private EntryDocument toDocument() {
            return new EntryDocument(idempotencyKey, email, credentialOfferUri, offerExpiresAt, onboardingUrl,
                    onboardingExpiresAt);
        }

        private static Entry fromDocument(EntryDocument document, String path) {
            if (document == null || document.idempotencyKey() == null) {
                throw new InvalidSectionException(path + "idempotency_key");
            }
            requirePair(document.credentialOfferUri(), document.offerExpiresAt(),
                    path + "credential_offer_uri", path + "offer_expires_at");
            requirePair(document.onboardingUrl(), document.onboardingExpiresAt(),
                    path + "onboarding_url", path + "onboarding_expires_at");
            return new Entry(document.idempotencyKey(), document.email(), document.credentialOfferUri(),
                    document.offerExpiresAt(), document.onboardingUrl(), document.onboardingExpiresAt());
        }

        /** A value and its expiry come together: one without the other is not an entry this module writes. */
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
            return "Entry[idempotencyKey=" + idempotencyKey + ", email=" + presence(email) + ", offer="
                    + presence(credentialOfferUri) + ", offerExpiresAt=" + offerExpiresAt + ", onboarding="
                    + presence(onboardingUrl) + ", onboardingExpiresAt=" + onboardingExpiresAt + "]";
        }
    }

    private static String presence(String secret) {
        return secret == null ? "none" : "redacted";
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Document(@JsonProperty("requests") Map<String, EntryDocument> requests) {

        @Override
        public String toString() {
            return "IssuanceRecord.Document[requests=" + (requests == null ? "none" : requests.keySet()) + "]";
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record EntryDocument(@JsonProperty("idempotency_key") UUID idempotencyKey,
                         @JsonProperty("email") String email,
                         @JsonProperty("credential_offer_uri") String credentialOfferUri,
                         @JsonProperty("offer_expires_at") Instant offerExpiresAt,
                         @JsonProperty("onboarding_url") String onboardingUrl,
                         @JsonProperty("onboarding_expires_at") Instant onboardingExpiresAt) {

        @Override
        public String toString() {
            return "IssuanceRecord.EntryDocument[idempotencyKey=" + idempotencyKey
                    + ", email, offer and onboarding redacted]";
        }
    }
}
