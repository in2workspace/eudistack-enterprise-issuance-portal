package es.in2.issuanceportal.journey.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The code that verifies the holder's email, as the journey keeps it (prd.md FR-22 to FR-24, design.md §3.6,
 * AD-15): 6 digits that last 10 minutes and allow 5 attempts, a new code voids the previous one, at least
 * 180 seconds between sends and 3 sends per journey. The code itself is never kept, only its hash. The
 * pending email is personal data, so the text form never shows it.
 *
 * <p>Once the right code is checked the email is verified: the code and its expiry are dropped, so it stops
 * working, while the sends stay so that the limits of the journey still apply to a new code.
 *
 * @param pendingEmail  the email the last code was sent to, which is the verified one when there is no code left
 * @param codeHash      see {@link #hash}; {@code null} once verified
 * @param codeExpiresAt when the code stops being valid; {@code null} once verified
 * @param attempts      wrong attempts made with the current code
 * @param sends         codes the journey has sent
 * @param lastSentAt    when the last code was sent
 * @param voidedCodeHashes the hashes of the codes a newer one replaced: they are not wrong but expired (ac.md AC-04,
 *                      openapi {@code expired_code}), which only a record of them can tell from a wrong code
 */
public record EmailVerification(String pendingEmail, String codeHash, Instant codeExpiresAt, int attempts,
                                int sends, Instant lastSentAt, List<String> voidedCodeHashes) {

    public static final Duration CODE_LIFETIME = Duration.ofMinutes(10);
    public static final int MAX_ATTEMPTS = 5;
    public static final Duration RESEND_INTERVAL = Duration.ofSeconds(180);
    public static final int MAX_SENDS = 3;

    private static final int CODE_DIGITS = 6;
    private static final int CODE_BOUND = 1_000_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** What came of checking a code. {@code next} is the state to keep: it only differs when something changed. */
    public record Check(Outcome outcome, EmailVerification next) {

        public enum Outcome { VERIFIED, INVALID_CODE, EXPIRED_CODE, TOO_MANY_ATTEMPTS }
    }

    /** Why a new code cannot be sent yet. {@code retryAfter} is empty when waiting does not help. */
    public record Refusal(Reason reason, Optional<Duration> retryAfter) {

        public enum Reason { TOO_MANY_CODES, RESEND_TOO_SOON }
    }

    public EmailVerification {
        Objects.requireNonNull(pendingEmail, "pendingEmail");
        Objects.requireNonNull(lastSentAt, "lastSentAt");
        voidedCodeHashes = voidedCodeHashes == null ? List.of() : List.copyOf(voidedCodeHashes);
        if ((codeHash == null) != (codeExpiresAt == null)) {
            throw new IllegalArgumentException("codeHash and codeExpiresAt come together");
        }
        if (attempts < 0 || sends < 1) {
            throw new IllegalArgumentException("attempts and sends");
        }
    }

    /** A verification that has voided no code. */
    public EmailVerification(String pendingEmail, String codeHash, Instant codeExpiresAt, int attempts, int sends,
                             Instant lastSentAt) {
        this(pendingEmail, codeHash, codeExpiresAt, attempts, sends, lastSentAt, List.of());
    }

    /**
     * Why this journey cannot send another code at {@code now}, if it cannot: the journey's limits only. The
     * limit per origin is not the journey's.
     *
     * @param previous what the journey had before, {@code null} when it never sent a code
     */
    public static Optional<Refusal> refusal(EmailVerification previous, Instant now) {
        if (previous == null) {
            return Optional.empty();
        }
        if (previous.sends >= MAX_SENDS) {
            return Optional.of(new Refusal(Refusal.Reason.TOO_MANY_CODES, Optional.empty()));
        }
        Instant allowedFrom = previous.resendAvailableAt();
        if (now.isBefore(allowedFrom)) {
            return Optional.of(new Refusal(Refusal.Reason.RESEND_TOO_SOON,
                    Optional.of(Duration.between(now, allowedFrom))));
        }
        return Optional.empty();
    }

    /**
     * The state after a code was sent: a new code with no attempts spent and one more send. The previous code
     * stops working because its hash is replaced.
     *
     * @param previous what the journey had before, {@code null} when it never sent a code
     */
    public static EmailVerification sent(String email, String codeHash, EmailVerification previous, Instant now) {
        int sends = previous == null ? 1 : previous.sends + 1;
        List<String> voided = previous == null ? List.of() : previous.voidedWithItsCode();
        return new EmailVerification(email, codeHash, now.plus(CODE_LIFETIME), 0, sends, now, voided);
    }

    /** Whether the text has the shape of a code: exactly 6 ASCII digits. */
    public static boolean isWellFormed(String code) {
        if (code == null || code.length() != CODE_DIGITS) {
            return false;
        }
        for (int i = 0; i < code.length(); i++) {
            if (code.charAt(i) < '0' || code.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /** Whether a code is pending: sent, not verified yet. */
    public boolean isPending() {
        return codeHash != null;
    }

    /** True once the right code was checked. */
    public boolean isVerified() {
        return codeHash == null;
    }

    /**
     * Checks a code that has the shape of one against the pending code (design.md section 3.6): with 5 failed
     * attempts the code no longer serves, whatever it is; after its expiry neither; otherwise the hashes are
     * compared in constant time. A wrong code spends an attempt; a code a newer one replaced is expired and
     * spends none.
     *
     * @throws IllegalStateException if no code is pending
     */
    public Check check(UUID journeyId, String code, Instant now) {
        if (!isPending()) {
            throw new IllegalStateException("No code pending");
        }
        if (attempts >= MAX_ATTEMPTS) {
            return new Check(Check.Outcome.TOO_MANY_ATTEMPTS, this);
        }
        if (!now.isBefore(codeExpiresAt)) {
            return new Check(Check.Outcome.EXPIRED_CODE, this);
        }
        String typed = hash(journeyId, code);
        if (sameHash(codeHash, typed)) {
            return new Check(Check.Outcome.VERIFIED, new EmailVerification(pendingEmail, null, null, 0, sends,
                    lastSentAt));
        }
        if (voidedCodeHashes.stream().anyMatch(voided -> sameHash(voided, typed))) {
            return new Check(Check.Outcome.EXPIRED_CODE, this);
        }
        return new Check(Check.Outcome.INVALID_CODE, new EmailVerification(pendingEmail, codeHash, codeExpiresAt,
                attempts + 1, sends, lastSentAt, voidedCodeHashes));
    }

    private static boolean sameHash(String stored, String typed) {
        return MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8), typed.getBytes(StandardCharsets.UTF_8));
    }

    /** Six digits, zeros included, from a cryptographically strong source. */
    public static String newCode() {
        return String.format("%0" + CODE_DIGITS + "d", RANDOM.nextInt(CODE_BOUND));
    }

    /**
     * SHA-256 of the journey's identifier and the code, in hex (ac.md decision 3). The identifier ties the hash to
     * its journey; the section that holds it is encrypted, so the table alone does not give the code.
     */
    public static String hash(UUID journeyId, String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((journeyId + ":" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    private List<String> voidedWithItsCode() {
        if (codeHash == null) {
            return voidedCodeHashes;
        }
        List<String> all = new ArrayList<>(voidedCodeHashes);
        all.add(codeHash);
        return all;
    }

    /** From when another code can be sent. */
    public Instant resendAvailableAt() {
        return lastSentAt.plus(RESEND_INTERVAL);
    }

    public int sendsLeft() {
        return Math.max(0, MAX_SENDS - sends);
    }

    @Override
    public String toString() {
        return "EmailVerification[email and code hash redacted, attempts=" + attempts + ", sends=" + sends + "]";
    }
}
