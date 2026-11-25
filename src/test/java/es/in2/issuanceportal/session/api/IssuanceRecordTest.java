package es.in2.issuanceportal.session.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IssuanceRecordTest {

    private static final UUID KEY = UUID.fromString("5b0b8c1e-6f43-4a38-9d0b-2f4c6b1a7e10");
    private static final UUID OTHER_KEY = UUID.fromString("7c1c9d2f-7054-4b49-8e1c-3a5d7c2b8f21");
    private static final Instant OFFER_EXPIRY = Instant.parse("2026-11-05T10:05:00Z");
    private static final Instant ONBOARDING_EXPIRY = Instant.parse("2026-11-05T10:30:00Z");
    private static final String EMAIL = "elena.ruiz@example.test";
    private static final String OFFER = "openid-credential-offer://?credential_offer_uri=https%3A%2F%2Fsecret";
    private static final String ONBOARDING = "https://wallet.example.org/onboarding?code=secret-code";

    private static IssuanceRecord.Entry complete() {
        return new IssuanceRecord.Entry(KEY, EMAIL, OFFER, OFFER_EXPIRY, ONBOARDING, ONBOARDING_EXPIRY);
    }

    private static IssuanceRecord twoRequests() {
        return IssuanceRecord.empty().with("r1", complete()).with("r2", IssuanceRecord.Entry.withKey(OTHER_KEY, EMAIL));
    }

    @Test
    void fromJson_twoRequests_readsTheSameInTheSameOrder() {
        IssuanceRecord read = IssuanceRecord.fromJson(twoRequests().toJson());

        assertThat(read).isEqualTo(twoRequests());
        assertThat(read.requests().keySet()).containsExactly("r1", "r2");
        assertThat(read.entry("r1")).contains(complete());
        assertThat(read.entry("r3")).isEmpty();
    }

    @Test
    void fromJson_keyOnlyEntry_readsTheSameWithoutOfferNorOnboarding() {
        IssuanceRecord.Entry key = IssuanceRecord.fromJson(twoRequests().toJson()).entry("r2").orElseThrow();

        assertThat(key).isEqualTo(IssuanceRecord.Entry.withKey(OTHER_KEY, EMAIL));
        assertThat(key.offerUri()).isEmpty();
        assertThat(key.onboarding()).isEmpty();
    }

    @Test
    void with_anotherEntryOfTheSameRequest_replacesItAndKeepsTheOthers() {
        IssuanceRecord changed = twoRequests().with("r2", complete());

        assertThat(changed.entry("r2")).contains(complete());
        assertThat(changed.entry("r1")).contains(complete());
        assertThat(twoRequests().entry("r2")).contains(IssuanceRecord.Entry.withKey(OTHER_KEY, EMAIL));
    }

    @Test
    void toJson_record_usesTheContractNamesPerRequest() {
        assertThat(twoRequests().toJson()).startsWith("{\"requests\":{\"r1\":{\"idempotency_key\":\"" + KEY + "\"")
                .contains("\"email\":\"" + EMAIL + "\"", "\"credential_offer_uri\":",
                        "\"offer_expires_at\":\"2026-11-05T10:05:00Z\"", "\"onboarding_url\":",
                        "\"onboarding_expires_at\":\"2026-11-05T10:30:00Z\"", "\"r2\":{\"idempotency_key\":\""
                                + OTHER_KEY + "\"");
        assertThat(IssuanceRecord.empty().with("r2", IssuanceRecord.Entry.withKey(OTHER_KEY, EMAIL)).toJson())
                .doesNotContain("offer", "onboarding");
    }

    @Test
    void toString_record_hidesTheBearerSecrets() {
        assertThat(twoRequests().toString()).doesNotContain(OFFER, ONBOARDING, EMAIL, "secret", KEY.toString())
                .contains("r1", "r2");
        assertThat(complete().toString()).doesNotContain(OFFER, ONBOARDING, EMAIL, "secret").contains(KEY.toString());
        assertThat(new IssuanceRecord.EntryDocument(KEY, EMAIL, OFFER, OFFER_EXPIRY, ONBOARDING, ONBOARDING_EXPIRY)
                .toString()).doesNotContain(OFFER, ONBOARDING, EMAIL, "secret");
    }

    @Test
    void isForEmail_entryWithItsEmail_isTheOnlyOneThatIs() {
        IssuanceRecord.Entry withEmail = IssuanceRecord.Entry.withKey(KEY, EMAIL);

        assertThat(withEmail.isForEmail(EMAIL)).isTrue();
        assertThat(withEmail.isForEmail("other@example.test")).isFalse();
        assertThat(IssuanceRecord.Entry.withKey(KEY, null).isForEmail(EMAIL)).isFalse();
    }

    @Test
    void fromJson_sectionOfASingleRequestWrittenBeforeThisShape_namesRequests() {
        assertInvalid("{\"idempotency_key\":\"" + KEY + "\",\"credential_offer_uri\":\"x\","
                + "\"offer_expires_at\":\"2026-11-05T10:05:00Z\"}", "requests");
    }

    @Test
    void fromJson_entryWithoutIdempotencyKey_namesTheField() {
        assertInvalid("{\"requests\":{\"r1\":{\"credential_offer_uri\":\"x\","
                + "\"offer_expires_at\":\"2026-11-05T10:05:00Z\"}}}", "requests.r1.idempotency_key");
    }

    @Test
    void fromJson_keyThatIsNotAUuid_namesTheField() {
        assertInvalid("{\"requests\":{\"r1\":{\"idempotency_key\":\"not-a-uuid\"}}}", "requests.r1.idempotency_key");
    }

    @Test
    void fromJson_offerWithoutExpiry_namesTheExpiry() {
        assertInvalid("{\"requests\":{\"r1\":{\"idempotency_key\":\"" + KEY + "\",\"credential_offer_uri\":\"x\"}}}",
                "requests.r1.offer_expires_at");
    }

    @Test
    void fromJson_onboardingWithoutExpiry_namesTheExpiry() {
        assertInvalid("{\"requests\":{\"r1\":{\"idempotency_key\":\"" + KEY + "\",\"onboarding_url\":\"x\"}}}",
                "requests.r1.onboarding_expires_at");
    }

    @Test
    void fromJson_expiryWithoutValue_namesTheValue() {
        assertInvalid("{\"requests\":{\"r1\":{\"idempotency_key\":\"" + KEY
                + "\",\"onboarding_expires_at\":\"2026-11-05T10:30:00Z\"}}}", "requests.r1.onboarding_url");
    }

    @Test
    void fromJson_requestIdOfMoreThanSixtyFourCharacters_namesRequests() {
        assertInvalid("{\"requests\":{\"" + "r".repeat(65) + "\":{\"idempotency_key\":\"" + KEY + "\"}}}", "requests");
    }

    @Test
    void fromJson_blankContent_namesTheDocument() {
        assertInvalid(" ", "$");
    }

    private static void assertInvalid(String json, String field) {
        assertThatThrownBy(() -> IssuanceRecord.fromJson(json))
                .isInstanceOf(InvalidSectionException.class)
                .satisfies(e -> assertThat(((InvalidSectionException) e).field()).isEqualTo(field))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("secret"));
    }
}
