package es.in2.issuanceportal.session.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailVerificationRecordTest {

    private static final String ADDRESS = "lucia.prueba@example.org";
    private static final Instant SENT = Instant.parse("2026-11-11T10:00:00Z");
    private static final EmailVerificationRecord RECORD = new EmailVerificationRecord(ADDRESS, "abc123",
            SENT.plusSeconds(600), 2, 1, SENT);

    @Test
    void fromJson_writtenRecord_readsTheSame() {
        assertThat(EmailVerificationRecord.fromJson(RECORD.toJson())).isEqualTo(RECORD);
    }

    @Test
    void toJson_record_usesTheContractNames() {
        assertThat(RECORD.toJson()).contains("\"pending_email\":\"" + ADDRESS + "\"", "\"code_hash\":\"abc123\"",
                "\"code_expires_at\":\"2026-11-11T10:10:00Z\"", "\"attempts\":2", "\"sends\":1",
                "\"last_sent_at\":\"2026-11-11T10:00:00Z\"");
    }

    @Test
    void toString_record_hidesTheAddressAndTheHash() {
        assertThat(RECORD.toString()).doesNotContain(ADDRESS, "lucia", "abc123");
        assertThat(new EmailVerificationRecord.Document(ADDRESS, "abc123", SENT, 0, 1, SENT).toString())
                .doesNotContain(ADDRESS, "lucia", "abc123");
    }

    @Test
    void fromJson_withoutPendingEmail_namesTheField() {
        assertInvalid("{\"code_hash\":\"h\",\"code_expires_at\":\"2026-11-11T10:10:00Z\",\"attempts\":0,"
                + "\"sends\":1,\"last_sent_at\":\"2026-11-11T10:00:00Z\"}", "pending_email");
    }

    @Test
    void fromJson_withoutCodeHash_namesTheField() {
        assertInvalid("{\"pending_email\":\"a@b.co\",\"code_expires_at\":\"2026-11-11T10:10:00Z\",\"attempts\":0,"
                + "\"sends\":1,\"last_sent_at\":\"2026-11-11T10:00:00Z\"}", "code_hash");
    }

    @Test
    void fromJson_noSendCounted_namesTheField() {
        assertInvalid("{\"pending_email\":\"a@b.co\",\"code_hash\":\"h\",\"code_expires_at\":\"2026-11-11T10:10:00Z\","
                + "\"attempts\":0,\"sends\":0,\"last_sent_at\":\"2026-11-11T10:00:00Z\"}", "sends");
    }

    @Test
    void fromJson_withoutLastSentAt_namesTheField() {
        assertInvalid("{\"pending_email\":\"a@b.co\",\"code_hash\":\"h\",\"code_expires_at\":\"2026-11-11T10:10:00Z\","
                + "\"attempts\":0,\"sends\":1}", "last_sent_at");
    }

    private static void assertInvalid(String json, String field) {
        assertThatThrownBy(() -> EmailVerificationRecord.fromJson(json))
                .isInstanceOf(InvalidSectionException.class)
                .extracting(e -> ((InvalidSectionException) e).field()).isEqualTo(field);
    }
}
