package es.in2.issuanceportal.journey.application.port;

import es.in2.issuanceportal.journey.domain.EmailLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailCodeMessageTest {

    @Test
    @DisplayName("AC-01 — of_spanish_carriesPortalNameCodeExpiryAndALineForWhoDidNotAsk")
    void of_spanish_carriesPortalNameCodeExpiryAndALineForWhoDidNotAsk() {
        EmailCodeMessage message = EmailCodeMessage.of("ana@example.test", "012345", "DoctorID", EmailLanguage.ES);

        assertThat(message.to()).isEqualTo("ana@example.test");
        assertThat(message.subject()).isEqualTo("Tu código de verificación");
        assertThat(message.body()).contains("DoctorID", "012345", "10 minutos", "Si no lo has pedido tú");
    }

    @Test
    @DisplayName("EC-06 — of_english_usesTheEnglishTemplate")
    void of_english_usesTheEnglishTemplate() {
        EmailCodeMessage message = EmailCodeMessage.of("ana@example.test", "012345", "DoctorID", EmailLanguage.EN);

        assertThat(message.subject()).isEqualTo("Your verification code");
        assertThat(message.body()).contains("DoctorID", "012345", "10 minutes", "If you did not ask for it");
    }

    @Test
    void of_anyLanguage_hasNoLinks() {
        for (EmailLanguage language : EmailLanguage.values()) {
            assertThat(EmailCodeMessage.of("ana@example.test", "012345", "DoctorID", language).body())
                    .doesNotContain("http", "://", "www.");
        }
    }

    @Test
    void toString_message_hidesTheAddressAndTheCode() {
        assertThat(EmailCodeMessage.of("ana@example.test", "012345", "DoctorID", EmailLanguage.ES).toString())
                .doesNotContain("ana@example.test", "012345");
    }
}
