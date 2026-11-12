package es.in2.issuanceportal.session;

import es.in2.issuanceportal.session.api.HolderRef;

import java.security.SecureRandom;
import java.util.HexFormat;

/** Holder references for tests: random ones, as the HMAC of different tokens gives. */
public final class Holders {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Holders() {
    }

    public static HolderRef next() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HolderRef.of(HexFormat.of().formatHex(bytes));
    }
}
