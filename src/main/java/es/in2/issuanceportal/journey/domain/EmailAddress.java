package es.in2.issuanceportal.journey.domain;

import java.util.regex.Pattern;

/**
 * The email the holder confirms (prd.md FR-05): where the Issuer delivers the offer and the holder's wallet
 * account email. Valid by construction. It is personal data, so the text form never shows it.
 */
public record EmailAddress(String value) {

    private static final int MAX_LENGTH = 254;
    private static final Pattern LOCAL_PART = Pattern.compile("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+");
    // One domain label; the domain is split on dots first, so no repeated group can backtrack.
    private static final Pattern DOMAIN_LABEL = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?");

    public EmailAddress {
        if (value == null || value.isBlank()) {
            throw new InvalidEmailException(InvalidEmailException.Reason.REQUIRED);
        }
        if (value.length() > MAX_LENGTH || !hasEmailShape(value)) {
            throw new InvalidEmailException(InvalidEmailException.Reason.INVALID);
        }
    }

    private static boolean hasEmailShape(String value) {
        int at = value.indexOf('@');
        if (at <= 0 || at != value.lastIndexOf('@') || !LOCAL_PART.matcher(value.substring(0, at)).matches()) {
            return false;
        }
        String[] labels = value.substring(at + 1).split("\\.", -1);
        if (labels.length < 2) {
            return false;
        }
        for (String label : labels) {
            if (!DOMAIN_LABEL.matcher(label).matches()) {
                return false;
            }
        }
        return true;
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
