package es.in2.issuanceportal.journey.infrastructure.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The TLS rules of the mail session, checked at startup on the shipped configuration with one setting changed. */
class SmtpTlsPolicyTest {

    private static final String SMTP = "spring.mail.properties.mail.smtp.";

    private ApplicationContextRunner shippedConfigWithSmtp() {
        return shippedConfig(MailProperties.Provider.SMTP);
    }

    private ApplicationContextRunner shippedConfig(MailProperties.Provider provider) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
                .withUserConfiguration(IncidentMailConfiguration.class, EmailCodeMailConfiguration.class)
                .withBean(MailProperties.class, () -> new MailProperties(provider))
                .withBean(EmailBrandingProperties.class,
                        () -> new EmailBrandingProperties("https://assets.example.org/tenants", Map.of()))
                .withPropertyValues(SMTP + "from=noreply@mail.stg.eudistack.net");
    }

    @Test
    @DisplayName("F-M1 — start_shippedConfiguration_meetsThePolicy")
    void start_shippedConfiguration_meetsThePolicy() {
        shippedConfigWithSmtp().run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("F-M1 — start_implicitTlsInsteadOfStartTls_meetsThePolicy")
    void start_implicitTlsInsteadOfStartTls_meetsThePolicy() {
        shippedConfigWithSmtp().withPropertyValues(SMTP + "starttls.required=false", SMTP + "ssl.enable=true")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest(name = "{0}={1} fails naming {2}")
    @CsvSource({
            SMTP + "ssl.trust, *, mail.smtp.ssl.trust",
            SMTP + "ssl.trust, smtp.example.org, mail.smtp.ssl.trust",
            SMTP + "ssl.socketFactory.class, javax.net.ssl.SSLSocketFactory, mail.smtp.ssl.socketFactory.class",
            SMTP + "socketFactory.class, javax.net.ssl.SSLSocketFactory, mail.smtp.socketFactory.class",
            SMTP + "starttls.required, false, mail.smtp.starttls.required",
            SMTP + "ssl.checkserveridentity, false, mail.smtp.ssl.checkserveridentity",
            "spring.mail.protocol, smtps, spring.mail.protocol"})
    @DisplayName("F-M1 — start_weakenedSetting_failsNamingIt")
    void start_weakenedSetting_failsNamingIt(String property, String value, String named) {
        shippedConfigWithSmtp().withPropertyValues(property + "=" + value)
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .rootCause().hasMessageContaining(named));
    }

    @Test
    @DisplayName("F-M1 — start_localProvider_doesNotCheckTheMailSession")
    void start_localProvider_doesNotCheckTheMailSession() {
        shippedConfig(MailProperties.Provider.LOCAL)
                .withPropertyValues(SMTP + "ssl.trust=*")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
