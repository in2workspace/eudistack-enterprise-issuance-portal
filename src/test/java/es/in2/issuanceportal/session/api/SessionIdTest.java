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
    void hasTwoHundredFiftySixRandomBits() {
        SessionId id = SessionId.generate();

        assertThat(Base64.getUrlDecoder().decode(id.value())).hasSize(32);
    }

    @Test
    void neverRepeats() {
        Set<String> values = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            values.add(SessionId.generate().value());
        }

        assertThat(values).hasSize(10_000);
    }

    @Test
    void parsesWhatItGenerated() {
        SessionId id = SessionId.generate();

        assertThat(SessionId.parse(id.value())).contains(id);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"short", "with spaces in it and long enough to pass the length check..",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA+"})
    void rejectsAnythingThisServiceDidNotIssue(String raw) {
        assertThat(SessionId.parse(raw)).isEmpty();
    }

    @Test
    void theHashIsSha256InHexAndDiffersFromTheValue() {
        SessionId id = SessionId.generate();

        assertThat(id.hash()).hasSize(64).matches("[0-9a-f]{64}").isNotEqualTo(id.value());
    }

    @Test
    void neverPrintsItsValue() {
        SessionId id = SessionId.generate();

        assertThat(id.toString()).doesNotContain(id.value());
    }
}
