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

    // --- the check: AC-02, AC-03, AC-04 ---

    private static final UUID JOURNEY = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private static EmailVerification pending(String code, int attempts) {
        return new EmailVerification(EMAIL, EmailVerification.hash(JOURNEY, code), NOW.plus(Duration.ofMinutes(10)),
                attempts, 1, NOW);
    }

    @Test
    @DisplayName("AC-02 — check_rightCode_isVerifiedAndDropsTheCodeKeepingTheSends")
    void check_rightCode_isVerifiedAndDropsTheCodeKeepingTheSends() {
        EmailVerification.Check check = pending("123456", 2).check(JOURNEY, "123456", NOW.plusSeconds(30));

        assertThat(check.outcome()).isEqualTo(EmailVerification.Check.Outcome.VERIFIED);
        assertThat(check.next().isVerified()).isTrue();
        assertThat(check.next().isPending()).isFalse();
        assertThat(check.next().codeHash()).isNull();
        assertThat(check.next().codeExpiresAt()).isNull();
        assertThat(check.next().pendingEmail()).isEqualTo(EMAIL);
        assertThat(check.next().sends()).isEqualTo(1);
        assertThat(check.next().lastSentAt()).isEqualTo(NOW);
        assertThat(check.next().resendAvailableAt()).isEqualTo(NOW.plusSeconds(180));
    }

    @Test
    @DisplayName("AC-03 — check_wrongCode_spendsOneAttempt")
    void check_wrongCode_spendsOneAttempt() {
        EmailVerification.Check check = pending("123456", 2).check(JOURNEY, "654321", NOW.plusSeconds(30));

        assertThat(check.outcome()).isEqualTo(EmailVerification.Check.Outcome.INVALID_CODE);
        assertThat(check.next().attempts()).isEqualTo(3);
        assertThat(check.next().isPending()).isTrue();
    }

    @Test
    @DisplayName("AC-03 — check_fiveAttemptsSpent_rejectsTheRightCodeToo")
    void check_fiveAttemptsSpent_rejectsTheRightCodeToo() {
        EmailVerification spent = pending("123456", 5);

        EmailVerification.Check check = spent.check(JOURNEY, "123456", NOW.plusSeconds(30));

        assertThat(check.outcome()).isEqualTo(EmailVerification.Check.Outcome.TOO_MANY_ATTEMPTS);
        assertThat(check.next()).isSameAs(spent);
    }

    @Test
    @DisplayName("AC-03 — check_fourthWrongThenFifth_reachesFiveAndTheNextIsTooManyAttempts")
    void check_fourthWrongThenFifth_reachesFiveAndTheNextIsTooManyAttempts() {
        EmailVerification fifth = pending("123456", 4).check(JOURNEY, "000000", NOW).next();

        assertThat(fifth.attempts()).isEqualTo(5);
        assertThat(fifth.check(JOURNEY, "000000", NOW).outcome())
                .isEqualTo(EmailVerification.Check.Outcome.TOO_MANY_ATTEMPTS);
    }

    @Test
    @DisplayName("AC-03 — check_tenMinutesAfterTheSend_rejectsTheRightCodeAsExpired")
    void check_tenMinutesAfterTheSend_rejectsTheRightCodeAsExpired() {
        EmailVerification code = pending("123456", 0);

        assertThat(code.check(JOURNEY, "123456", NOW.plus(Duration.ofMinutes(10)).minusSeconds(1)).outcome())
                .isEqualTo(EmailVerification.Check.Outcome.VERIFIED);
        EmailVerification.Check expired = code.check(JOURNEY, "123456", NOW.plus(Duration.ofMinutes(10)));
        assertThat(expired.outcome()).isEqualTo(EmailVerification.Check.Outcome.EXPIRED_CODE);
        assertThat(expired.next()).isSameAs(code);
    }

    @Test
    @DisplayName("AC-04 — check_codeReplacedByANewerOne_isExpiredAndSpendsNoAttempt")
    void check_codeReplacedByANewerOne_isExpiredAndSpendsNoAttempt() {
        EmailVerification first = pending("111111", 3);
        EmailVerification second = EmailVerification.sent(EMAIL, EmailVerification.hash(JOURNEY, "222222"), first,
                NOW.plusSeconds(200));
        EmailVerification third = EmailVerification.sent(EMAIL, EmailVerification.hash(JOURNEY, "333333"), second,
                NOW.plusSeconds(400));

        assertThat(third.check(JOURNEY, "111111", NOW.plusSeconds(410)).outcome())
                .isEqualTo(EmailVerification.Check.Outcome.EXPIRED_CODE);
        assertThat(third.check(JOURNEY, "222222", NOW.plusSeconds(410)).outcome())
                .isEqualTo(EmailVerification.Check.Outcome.EXPIRED_CODE);
        assertThat(third.check(JOURNEY, "222222", NOW.plusSeconds(410)).next().attempts()).isZero();
        assertThat(third.check(JOURNEY, "333333", NOW.plusSeconds(410)).outcome())
                .isEqualTo(EmailVerification.Check.Outcome.VERIFIED);
        assertThat(third.voidedCodeHashes()).hasSize(2);
    }

    @Test
    void check_codeOfAnotherJourney_isWrong() {
        EmailVerification code = pending("123456", 0);

        assertThat(code.check(UUID.randomUUID(), "123456", NOW).outcome())
                .isEqualTo(EmailVerification.Check.Outcome.INVALID_CODE);
    }

    @Test
    void check_nothingPending_isAnIllegalState() {
        EmailVerification verified = pending("123456", 0).check(JOURNEY, "123456", NOW).next();

        assertThatThrownBy(() -> verified.check(JOURNEY, "123456", NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("ES-02 — isWellFormed_onlySixAsciiDigits")
    void isWellFormed_onlySixAsciiDigits() {
        assertThat(EmailVerification.isWellFormed("012345")).isTrue();
        for (String bad : new String[] {null, "", "12345", "1234567", "12345a", " 12345", "12 456", "\u0661\u0662\u0663\u0664\u0665\u0666"}) {
            assertThat(EmailVerification.isWellFormed(bad)).as("%s", bad).isFalse();
        }
    }

    @Test
    void constructor_hashWithoutExpiry_isRejected() {
        assertThatThrownBy(() -> new EmailVerification(EMAIL, "h", null, 0, 1, NOW))
                .isInstanceOf(IllegalArgumentException.class);
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
