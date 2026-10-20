package es.in2.issuanceportal.session.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The identifier the browser holds in the {@code journey_session} cookie: 256 random bits. Only its
 * hash is ever stored, and it never appears in logs.
 */
public final class SessionId {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int BYTES = 32;
    private static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    private final String value;

    private SessionId(String value) {
        this.value = value;
    }

    public static SessionId generate() {
        byte[] bytes = new byte[BYTES];
        RANDOM.nextBytes(bytes);
        return new SessionId(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    /** The identifier sent by the browser, only if it has the shape of one this service issued. */
    public static Optional<SessionId> parse(String raw) {
        return raw != null && FORMAT.matcher(raw).matches() ? Optional.of(new SessionId(raw)) : Optional.empty();
    }

    /** The value for the cookie. */
    public String value() {
        return value;
    }

    /** SHA-256 in hex: what the database keeps instead of the identifier. */
    public String hash() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SessionId that && MessageDigest.isEqual(
                value.getBytes(StandardCharsets.US_ASCII), that.value.getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "SessionId[redacted]";
    }
}
