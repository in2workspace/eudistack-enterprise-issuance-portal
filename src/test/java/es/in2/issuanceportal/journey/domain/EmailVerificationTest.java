package es.in2.issuanceportal.journey.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailVerificationTest {

    private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");
    private static final String EMAIL = "elena.ruiz@example.test";

    @Test
    @DisplayName("AC-01 — sent_firstCode_expiresInTenMinutesWithOneSend")
    void sent_firstCode_expiresInTenMinutesWithOneSend() {
        EmailVerification first = EmailVerification.sent(EMAIL, "hash-1", null, NOW);

        assertThat(first.pendingEmail()).isEqualTo(EMAIL);
        assertThat(first.codeExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        assertThat(first.attempts()).isZero();
        assertThat(first.sends()).isEqualTo(1);
        assertThat(first.lastSentAt()).isEqualTo(NOW);
        assertThat(first.sendsLeft()).isEqualTo(2);
        assertThat(first.resendAvailableAt()).isEqualTo(NOW.plusSeconds(180));
    }

    @Test
    @DisplayName("AC-04 — sent_newCode_replacesTheHashResetsAttemptsAndCountsTheSend")
    void sent_newCode_replacesTheHashResetsAttemptsAndCountsTheSend() {
        EmailVerification spent = new EmailVerification(EMAIL, "hash-1", NOW.plusSeconds(600), 4, 1, NOW);

        EmailVerification second = EmailVerification.sent("other@example.test", "hash-2", spent,
                NOW.plusSeconds(200));

        assertThat(second.codeHash()).isEqualTo("hash-2");
        assertThat(second.pendingEmail()).isEqualTo("other@example.test");
        assertThat(second.attempts()).isZero();
        assertThat(second.sends()).isEqualTo(2);
        assertThat(second.codeExpiresAt()).isEqualTo(NOW.plusSeconds(200).plus(Duration.ofMinutes(10)));
    }

    @Test
    void refusal_neverSent_allowsTheSend() {
        assertThat(EmailVerification.refusal(null, NOW)).isEmpty();
    }

    @Test
    @DisplayName("AC-04 — refusal_lessThanThreeMinutesSinceTheLastSend_waitsTheRest")
    void refusal_lessThanThreeMinutesSinceTheLastSend_waitsTheRest() {
        EmailVerification last = EmailVerification.sent(EMAIL, "h", null, NOW);

        assertThat(EmailVerification.refusal(last, NOW.plusSeconds(100))).hasValueSatisfying(refusal -> {
            assertThat(refusal.reason()).isEqualTo(EmailVerification.Refusal.Reason.RESEND_TOO_SOON);
            assertThat(refusal.retryAfter()).contains(Duration.ofSeconds(80));
        });
    }

    @Test
    void refusal_exactlyThreeMinutesSinceTheLastSend_allowsTheSend() {
        EmailVerification last = EmailVerification.sent(EMAIL, "h", null, NOW);

        assertThat(EmailVerification.refusal(last, NOW.plusSeconds(180))).isEmpty();
    }

    @Test
    @DisplayName("AC-05 — refusal_threeSendsDone_isTooManyCodesWithNoWait")
    void refusal_threeSendsDone_isTooManyCodesWithNoWait() {
        EmailVerification third = new EmailVerification(EMAIL, "h", NOW.plusSeconds(600), 0, 3, NOW);

        assertThat(EmailVerification.refusal(third, NOW.plus(Duration.ofHours(1)))).hasValueSatisfying(refusal -> {
            assertThat(refusal.reason()).isEqualTo(EmailVerification.Refusal.Reason.TOO_MANY_CODES);
            assertThat(refusal.retryAfter()).isEmpty();
        });
        assertThat(third.sendsLeft()).isZero();
    }

    @Test
    void refusal_bothLimitsApply_theCountOfCodesComesFirst() {
        EmailVerification third = new EmailVerification(EMAIL, "h", NOW.plusSeconds(600), 0, 3, NOW);

        assertThat(EmailVerification.refusal(third, NOW.plusSeconds(1)).orElseThrow().reason())
                .isEqualTo(EmailVerification.Refusal.Reason.TOO_MANY_CODES);
    }

    @RepeatedTest(50)
    void newCode_always_isSixDigits() {
        assertThat(EmailVerification.newCode()).matches("\\d{6}");
    }

    @Test
    void hash_sameJourneyAndCode_isStableSha256Hex() {
        UUID journey = UUID.fromString("00000000-0000-0000-0000-000000000001");

        assertThat(EmailVerification.hash(journey, "123456")).isEqualTo(EmailVerification.hash(journey, "123456"))
                .matches("[0-9a-f]{64}");
    }

    @Test
    void hash_otherJourneyOrCode_differs() {
        UUID journey = UUID.randomUUID();

        assertThat(EmailVerification.hash(journey, "123456"))
                .isNotEqualTo(EmailVerification.hash(UUID.randomUUID(), "123456"))
                .isNotEqualTo(EmailVerification.hash(journey, "123457"));
    }

    @Test
    void toString_verification_hidesTheEmailAndTheHash() {
        EmailVerification verification = EmailVerification.sent(EMAIL, "secret-hash", null, NOW);

        assertThat(verification.toString()).doesNotContain(EMAIL, "elena", "secret-hash");
    }

    @Test
    void constructor_noSendCounted_isRejected() {
        assertThatThrownBy(() -> new EmailVerification(EMAIL, "h", NOW, 0, 0, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
