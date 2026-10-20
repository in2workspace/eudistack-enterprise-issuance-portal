package es.in2.issuanceportal.session.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SessionIdTest {

    @Test
    void generate_always_hasTwoHundredFiftySixBits() {
        SessionId id = SessionId.generate();

        assertThat(Base64.getUrlDecoder().decode(id.value())).hasSize(32);
    }

    @Test
    void generate_tenThousandTimes_neverRepeats() {
        Set<String> values = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            values.add(SessionId.generate().value());
        }

        assertThat(values).hasSize(10_000);
    }

    @Test
    void parse_generatedValue_returnsSameId() {
        SessionId id = SessionId.generate();

        assertThat(SessionId.parse(id.value())).contains(id);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"short", "with spaces in it and long enough to pass the length check..",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA+"})
    void parse_foreignValue_returnsEmpty(String raw) {
        assertThat(SessionId.parse(raw)).isEmpty();
    }

    @Test
    void hash_anyId_isSha256HexDifferentFromValue() {
        SessionId id = SessionId.generate();

        assertThat(id.hash()).hasSize(64).matches("[0-9a-f]{64}").isNotEqualTo(id.value());
    }

    @Test
    void toString_anyId_doesNotRevealValue() {
        SessionId id = SessionId.generate();

        assertThat(id.toString()).doesNotContain(id.value());
    }
}
