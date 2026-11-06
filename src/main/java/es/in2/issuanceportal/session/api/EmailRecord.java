package es.in2.issuanceportal.session.api;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Contract of the {@link Section#EMAIL} section: the holder's email, written by the Journey module when
 * the holder confirms. It is personal data, so the text form never shows it.
 *
 * @param address the holder's email address
 */
public record EmailRecord(String address) {

    public EmailRecord {
        Objects.requireNonNull(address, "email");
        if (address.isBlank()) {
            throw new IllegalArgumentException("email");
        }
    }

    public String toJson() {
        return SectionJson.write(new Document(address));
    }

    /**
     * Reads the section.
     *
     * @throws InvalidSectionException naming the field that does not have the shape of the contract
     */
    public static EmailRecord fromJson(String json) {
        Document document = SectionJson.read(json, Document.class);
        if (document.email() == null || document.email().isBlank()) {
            throw new InvalidSectionException("email");
        }
        return new EmailRecord(document.email());
    }

    @Override
    public String toString() {
        return "EmailRecord[email redacted]";
    }

    record Document(@JsonProperty("email") String email) {

        @Override
        public String toString() {
            return "EmailRecord.Document[email redacted]";
        }
    }
}
