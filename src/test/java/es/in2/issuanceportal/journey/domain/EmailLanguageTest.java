package es.in2.issuanceportal.journey.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class EmailLanguageTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "en|EN",
            "en-GB|EN",
            "EN-us|EN",
            "es-ES|ES",
            "fr, en;q=0.8|EN",
            "en;q=0.5, es;q=0.9|ES",
            "de, fr;q=0.9|ES",
            "*|ES",
            "en;q=0|ES"})
    @DisplayName("EC-06 — fromAcceptLanguage_header_picksTheFirstLanguageTheServiceHas")
    void fromAcceptLanguage_header_picksTheFirstLanguageTheServiceHas(String header, EmailLanguage expected) {
        assertThat(EmailLanguage.fromAcceptLanguage(header)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", ";;;", "en;q=abc", ","})
    @DisplayName("EC-06 — fromAcceptLanguage_missingOrMalformed_isSpanish")
    void fromAcceptLanguage_missingOrMalformed_isSpanish(String header) {
        assertThat(EmailLanguage.fromAcceptLanguage(header)).isEqualTo(EmailLanguage.ES);
    }
}
