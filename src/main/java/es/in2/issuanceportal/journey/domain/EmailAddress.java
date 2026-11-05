package es.in2.issuanceportal.journey.domain;

import java.util.regex.Pattern;

/**
 * The email the holder confirms (prd.md FR-05): where the Issuer delivers the offer and the holder's wallet
 * account email. Valid by construction. It is personal data, so the text form never shows it.
 */
public record EmailAddress(String value) {

    private static final int MAX_LENGTH = 254;
    private static final Pattern SHAPE = Pattern.compile(
            "^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
                    + "(?:[.][A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$");

    public EmailAddress {
        if (value == null || value.isBlank()) {
            throw new InvalidEmailException(InvalidEmailException.Reason.REQUIRED);
        }
        if (value.length() > MAX_LENGTH || !SHAPE.matcher(value).matches()) {
            throw new InvalidEmailException(InvalidEmailException.Reason.INVALID);
        }
    }

    /** Reads what the holder sent, ignoring the whitespace around it. */
    public static EmailAddress parse(String raw) {
        return new EmailAddress(raw == null ? null : raw.strip());
    }

    @Override
    public String toString() {
        return "EmailAddress[redacted]";
    }
}
