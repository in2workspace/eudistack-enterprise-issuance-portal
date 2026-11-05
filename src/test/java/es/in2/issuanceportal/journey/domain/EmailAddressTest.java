package es.in2.issuanceportal.journey.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailAddressTest {

    @ParameterizedTest
    @ValueSource(strings = {"elena.ruiz@example.test", "a+tag@sub.example.es", "x_y-z@e-x.example.org"})
    void parse_wellFormed_keepsIt(String value) {
        assertThat(EmailAddress.parse(value).value()).isEqualTo(value);
    }

    @Test
    void parse_surroundingWhitespace_isIgnored() {
        assertThat(EmailAddress.parse("  elena.ruiz@example.test\n").value()).isEqualTo("elena.ruiz@example.test");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void parse_missingOrBlank_isRequired(String value) {
        assertThatThrownBy(() -> EmailAddress.parse(value))
                .isInstanceOfSatisfying(InvalidEmailException.class,
                        e -> assertThat(e.reason()).isEqualTo(InvalidEmailException.Reason.REQUIRED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"elena", "elena@", "@example.test", "elena@example", "elena@@example.test",
            "ele na@example.test", "elena@.example.test", "elena@example..test", "elena@example.test.", "123"})
    void parse_notAnEmail_isInvalid(String value) {
        assertThatThrownBy(() -> EmailAddress.parse(value))
                .isInstanceOfSatisfying(InvalidEmailException.class,
                        e -> assertThat(e.reason()).isEqualTo(InvalidEmailException.Reason.INVALID));
    }

    @Test
    void parse_tooLong_isInvalid() {
        String longLocal = "a".repeat(250) + "@example.test";

        assertThatThrownBy(() -> EmailAddress.parse(longLocal)).isInstanceOf(InvalidEmailException.class);
    }

    @Test
    void toString_neverShowsTheAddress() {
        assertThat(EmailAddress.parse("elena.ruiz@example.test").toString()).doesNotContain("elena");
    }
}
