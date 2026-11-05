package es.in2.issuanceportal.session.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IssuanceRecordTest {

    private static final UUID KEY = UUID.fromString("5b0b8c1e-6f43-4a38-9d0b-2f4c6b1a7e10");
    private static final Instant OFFER_EXPIRY = Instant.parse("2026-11-05T10:05:00Z");
    private static final Instant ONBOARDING_EXPIRY = Instant.parse("2026-11-05T10:30:00Z");
    private static final String OFFER = "openid-credential-offer://?credential_offer_uri=https%3A%2F%2Fsecret";
    private static final String ONBOARDING = "https://wallet.example.org/onboarding?code=secret-code";

    private static IssuanceRecord complete() {
        return new IssuanceRecord(KEY, OFFER, OFFER_EXPIRY, ONBOARDING, ONBOARDING_EXPIRY);
    }

    @Test
    void fromJson_completeRecord_readsTheSame() {
        assertThat(IssuanceRecord.fromJson(complete().toJson())).isEqualTo(complete());
    }

    @Test
    void fromJson_keyOnly_readsTheSameWithoutOfferNorOnboarding() {
        IssuanceRecord record = IssuanceRecord.fromJson(IssuanceRecord.withKey(KEY).toJson());

        assertThat(record).isEqualTo(IssuanceRecord.withKey(KEY));
        assertThat(record.offerUri()).isEmpty();
        assertThat(record.onboarding()).isEmpty();
    }

    @Test
    void toJson_record_usesTheContractNames() {
        assertThat(complete().toJson()).contains("\"idempotency_key\":\"" + KEY + "\"",
                "\"credential_offer_uri\":", "\"offer_expires_at\":\"2026-11-05T10:05:00Z\"",
                "\"onboarding_url\":", "\"onboarding_expires_at\":\"2026-11-05T10:30:00Z\"");
        assertThat(IssuanceRecord.withKey(KEY).toJson()).doesNotContain("offer", "onboarding");
    }

    @Test
    void toString_record_hidesTheBearerSecrets() {
        assertThat(complete().toString()).doesNotContain(OFFER, ONBOARDING, "secret")
                .contains(KEY.toString());
        assertThat(new IssuanceRecord.Document(KEY, OFFER, OFFER_EXPIRY, ONBOARDING, ONBOARDING_EXPIRY).toString())
                .doesNotContain(OFFER, ONBOARDING, "secret");
    }

    @Test
    void fromJson_withoutIdempotencyKey_namesTheField() {
        assertInvalid("{\"credential_offer_uri\":\"x\",\"offer_expires_at\":\"2026-11-05T10:05:00Z\"}",
                "idempotency_key");
    }

    @Test
    void fromJson_keyThatIsNotAUuid_namesTheField() {
        assertInvalid("{\"idempotency_key\":\"not-a-uuid\"}", "idempotency_key");
    }

    @Test
    void fromJson_offerWithoutExpiry_namesTheExpiry() {
        assertInvalid("{\"idempotency_key\":\"" + KEY + "\",\"credential_offer_uri\":\"x\"}",
                "offer_expires_at");
    }

    @Test
    void fromJson_onboardingWithoutExpiry_namesTheExpiry() {
        assertInvalid("{\"idempotency_key\":\"" + KEY + "\",\"onboarding_url\":\"x\"}",
                "onboarding_expires_at");
    }

    @Test
    void fromJson_expiryWithoutValue_namesTheValue() {
        assertInvalid("{\"idempotency_key\":\"" + KEY
                + "\",\"onboarding_expires_at\":\"2026-11-05T10:30:00Z\"}", "onboarding_url");
    }

    @Test
    void fromJson_blankContent_namesTheDocument() {
        assertInvalid(" ", "$");
    }

    private static void assertInvalid(String json, String field) {
        assertThatThrownBy(() -> IssuanceRecord.fromJson(json))
                .isInstanceOf(InvalidSectionException.class)
                .extracting(e -> ((InvalidSectionException) e).field()).isEqualTo(field);
    }
}
