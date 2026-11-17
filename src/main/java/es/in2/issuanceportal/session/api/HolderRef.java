package es.in2.issuanceportal.session.api;

import java.util.regex.Pattern;

/**
 * The holder of a journey as the service knows it (design.md §3.2): the hex of an HMAC-SHA256 of the token's {@code iss}
 * and {@code sub}. It identifies the holder without revealing the {@code sub}, and it never appears in logs.
 */
public record HolderRef(String value) {

    private static final Pattern FORMAT = Pattern.compile("^[0-9a-f]{64}$");

    public HolderRef {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("A holder reference is 64 lowercase hex characters");
        }
    }

    public static HolderRef of(String value) {
        return new HolderRef(value);
    }

    @Override
    public String toString() {
        return "HolderRef[redacted]";
    }
}
