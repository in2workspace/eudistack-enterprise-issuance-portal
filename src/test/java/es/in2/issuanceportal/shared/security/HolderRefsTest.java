package es.in2.issuanceportal.shared.security;

import es.in2.issuanceportal.session.api.HolderRef;
import es.in2.issuanceportal.shared.error.ProblemCode;
import es.in2.issuanceportal.shared.error.ProblemException;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HolderRefsTest {

    private static final String KEY = "VGhpcyBpcyBhIHRlc3Qtb25seSBob2xkZXIgcmVmIGtleSE=";
    private static final String ISSUER = "https://sandbox.127.0.0.1.nip.io:4443/verifier";

    private final HolderRefs holders = new HolderRefs(new HolderRefProperties(KEY));

    @Test
    void of_issuerAndSubject_isTheHexOfTheHmacSha256OfBothSeparatedByANulByte() {
        // Computed outside the service: printf 'iss\0sub' | openssl dgst -sha256 -mac HMAC -macopt hexkey:<key>
        assertThat(holders.of(principal(ISSUER, "holder-1")).value())
                .isEqualTo("49bf1d98d4770ca3f4a71b0a2529192995353a1a93d5c1ddec3d9eb4128bf39f");
    }

    @Test
    void of_sameIssuerAndSubject_givesTheSameReference() {
        assertThat(holders.of(principal(ISSUER, "holder-1"))).isEqualTo(holders.of(principal(ISSUER, "holder-1")));
    }

    @Test
    void of_anotherSubjectOrIssuer_givesAnotherReference() {
        HolderRef reference = holders.of(principal(ISSUER, "holder-1"));

        assertThat(holders.of(principal(ISSUER, "holder-2"))).isNotEqualTo(reference);
        assertThat(holders.of(principal("https://kpmg.127.0.0.1.nip.io:4443/verifier", "holder-1"))).isNotEqualTo(reference);
    }

    @Test
    void of_boundaryBetweenIssuerAndSubjectMoved_givesAnotherReference() {
        assertThat(holders.of(principal("ab", "c"))).isNotEqualTo(holders.of(principal("a", "bc")));
    }

    @Test
    void of_anotherKey_givesAnotherReference() {
        String other = Base64.getEncoder().encodeToString(new byte[32]);

        assertThat(new HolderRefs(new HolderRefProperties(other)).of(principal(ISSUER, "holder-1")))
                .isNotEqualTo(holders.of(principal(ISSUER, "holder-1")));
    }

    @Test
    void of_tokenWithoutIssuerOrSubject_isUnauthenticated() {
        assertThatThrownBy(() -> holders.of(principal(ISSUER, null)))
                .isInstanceOfSatisfying(ProblemException.class,
                        e -> assertThat(e.problem()).isEqualTo(ProblemCode.UNAUTHENTICATED));
        assertThatThrownBy(() -> holders.of(principal(" ", "holder-1"))).isInstanceOf(ProblemException.class);
    }

    @Test
    void properties_keyShorterThan32Bytes_isRejected() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[31]);

        assertThatIllegalArgumentException().isThrownBy(() -> new HolderRefProperties(shortKey))
                .withMessageContaining("at least 32 bytes");
    }

    @Test
    void properties_keyThatIsNotBase64_isRejectedWithoutQuotingIt() {
        assertThatIllegalArgumentException().isThrownBy(() -> new HolderRefProperties("not base64 !!"))
                .withMessage("issuance-portal.holder-ref.key must be base64");
    }

    @Test
    void properties_toString_doesNotShowTheKey() {
        assertThat(new HolderRefProperties(KEY).toString()).doesNotContain(KEY);
    }

    private static HolderPrincipal principal(String issuer, String subject) {
        return new HolderPrincipal(issuer, subject, null, null, null, null, List.of(), null);
    }
}
