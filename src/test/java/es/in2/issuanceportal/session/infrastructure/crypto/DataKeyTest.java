package es.in2.issuanceportal.session.infrastructure.crypto;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class DataKeyTest {

    private static final byte[] KEY = {1, 2, 3, 4};
    private static final byte[] WRAPPED = {9, 8, 7};

    @Test
    void equals_sameContentsInDifferentArrays_isEqualWithSameHashCode() {
        DataKey one = new DataKey(KEY.clone(), WRAPPED.clone());
        DataKey other = new DataKey(KEY.clone(), WRAPPED.clone());

        assertThat(one).isEqualTo(other).hasSameHashCodeAs(other);
    }

    @Test
    void equals_differentWrappedKey_isNotEqual() {
        assertThat(new DataKey(KEY, WRAPPED)).isNotEqualTo(new DataKey(KEY, new byte[]{9, 8, 6}));
    }

    @Test
    void toString_anyKey_doesNotRevealTheKey() {
        String printed = new DataKey(KEY, WRAPPED).toString();

        assertThat(printed).isEqualTo("DataKey[redacted]")
                .doesNotContain(Base64.getEncoder().encodeToString(KEY));
    }
}
