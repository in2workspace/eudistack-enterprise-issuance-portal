package es.in2.issuanceportal.session.api;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Objects;

/**
 * Contract of the {@link Section#EMAIL_VERIFICATION} section (design.md §3.6): the code the Journey module
 * sent to the holder's email and the limits it counts. The code is never kept, only its hash. The pending
 * email is personal data, so the text form never shows it.
 *
 * @param pendingEmail  the email the last code was sent to
 * @param codeHash      hash of the code, never the code
 * @param codeExpiresAt when the code stops being valid
 * @param attempts      wrong attempts made with the current code
 * @param sends         codes the journey has sent
 * @param lastSentAt    when the last code was sent
 */
public record EmailVerificationRecord(String pendingEmail, String codeHash, Instant codeExpiresAt, int attempts,
                                      int sends, Instant lastSentAt) {

    public EmailVerificationRecord {
        Objects.requireNonNull(pendingEmail, "pending_email");
        Objects.requireNonNull(codeHash, "code_hash");
        Objects.requireNonNull(codeExpiresAt, "code_expires_at");
        Objects.requireNonNull(lastSentAt, "last_sent_at");
        if (pendingEmail.isBlank() || codeHash.isBlank()) {
            throw new IllegalArgumentException("pending_email and code_hash");
        }
        if (attempts < 0 || sends < 1) {
            throw new IllegalArgumentException("attempts and sends");
        }
    }

    public String toJson() {
        return SectionJson.write(new Document(pendingEmail, codeHash, codeExpiresAt, attempts, sends, lastSentAt));
    }

    /**
     * Reads the section.
     *
     * @throws InvalidSectionException naming the field that does not have the shape of the contract
     */
    public static EmailVerificationRecord fromJson(String json) {
        Document document = SectionJson.read(json, Document.class);
        requireText(document.pendingEmail(), "pending_email");
        requireText(document.codeHash(), "code_hash");
        if (document.codeExpiresAt() == null) {
            throw new InvalidSectionException("code_expires_at");
        }
        if (document.attempts() == null || document.attempts() < 0) {
            throw new InvalidSectionException("attempts");
        }
        if (document.sends() == null || document.sends() < 1) {
            throw new InvalidSectionException("sends");
        }
        if (document.lastSentAt() == null) {
            throw new InvalidSectionException("last_sent_at");
        }
        return new EmailVerificationRecord(document.pendingEmail(), document.codeHash(), document.codeExpiresAt(),
                document.attempts(), document.sends(), document.lastSentAt());
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidSectionException(field);
        }
    }

    @Override
    public String toString() {
        return "EmailVerificationRecord[email and code hash redacted, attempts=" + attempts + ", sends=" + sends + "]";
    }

    record Document(@JsonProperty("pending_email") String pendingEmail,
                    @JsonProperty("code_hash") String codeHash,
                    @JsonProperty("code_expires_at") Instant codeExpiresAt,
                    @JsonProperty("attempts") Integer attempts,
                    @JsonProperty("sends") Integer sends,
                    @JsonProperty("last_sent_at") Instant lastSentAt) {

        @Override
        public String toString() {
            return "EmailVerificationRecord.Document[email and code hash redacted]";
        }
    }
}
