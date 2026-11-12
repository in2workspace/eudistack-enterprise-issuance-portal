package es.in2.issuanceportal.shared.security;

import es.in2.issuanceportal.session.api.HolderRef;
import es.in2.issuanceportal.shared.error.ProblemCode;
import es.in2.issuanceportal.shared.error.ProblemException;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * Gives the {@link HolderRef} of the holder of a validated token: HMAC-SHA256 of {@code iss} and {@code sub} under the
 * environment's key (design.md §3.2). The two are separated by a character that cannot be part of either, so
 * different pairs never give the same input. A token without {@code iss} or {@code sub} is unauthenticated.
 */
@Component
public class HolderRefs {

    private static final String ALGORITHM = "HmacSHA256";
    private static final byte SEPARATOR = 0;

    private final SecretKeySpec key;

    HolderRefs(HolderRefProperties properties) {
        this.key = new SecretKeySpec(properties.keyBytes(), ALGORITHM);
    }

    public HolderRef of(HolderPrincipal holder) {
        if (isBlank(holder.issuer()) || isBlank(holder.subject())) {
            // A token the Verifier signed always has both; one without them cannot be tied to a holder.
            throw new ProblemException(ProblemCode.UNAUTHENTICATED);
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            mac.update(holder.issuer().getBytes(StandardCharsets.UTF_8));
            mac.update(SEPARATOR);
            mac.update(holder.subject().getBytes(StandardCharsets.UTF_8));
            return HolderRef.of(HexFormat.of().formatHex(mac.doFinal()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is always available", e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
