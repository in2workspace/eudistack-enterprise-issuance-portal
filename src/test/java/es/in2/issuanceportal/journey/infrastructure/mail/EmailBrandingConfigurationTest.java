package es.in2.issuanceportal.journey.infrastructure.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The branding file gives the tenants' image flags and nothing else: it is not a configuration source. */
class EmailBrandingConfigurationTest {

    private static final String HOSTILE_FILE = """
            spring:
              mail:
                host: attacker.example.org
                properties:
                  mail:
                    smtp:
                      ssl:
                        trust: "*"
                        checkserveridentity: false
                      starttls:
                        required: false
            management:
              endpoints:
                web:
                  exposure:
                    include: "*"
            email-branding:
              assets-base-url: http://attacker.example.org
              tenants:
                cgcom:
                  logo: true
                  header-background: true
                  social-links:
                    - { type: github, url: "https://github.com/x" }
                  support:
                    url: "https://example.org"
                kpmg:
                  logo: true
            """;

    @TempDir
    Path directory;

    private ApplicationContextRunner shippedConfigWithSmtp(String brandingLocation) {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
                .withUserConfiguration(IncidentMailConfiguration.class, EmailCodeMailConfiguration.class,
                        EmailBrandingConfiguration.class)
                .withBean(MailProperties.class, () -> new MailProperties(MailProperties.Provider.SMTP))
                .withPropertyValues("EMAIL_BRANDING_PATH=" + brandingLocation,
                        "email-branding.assets-base-url=https://assets.example.org/tenants",
                        "spring.mail.properties.mail.smtp.from=noreply@mail.stg.eudistack.net");
    }

    @Test
    @DisplayName("F-M1 — brandingFile_withOtherSpringProperties_bindsOnlyTheTenantsAndLeavesTheMailAlone")
    void brandingFile_withOtherSpringProperties_bindsOnlyTheTenantsAndLeavesTheMailAlone() throws IOException {
        Path file = Files.writeString(directory.resolve("email-branding.yaml"), HOSTILE_FILE);

        shippedConfigWithSmtp(file.toUri().toString()).run(context -> {
            // The service starts, so the TLS policy found the shipped settings intact.
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getProperty("spring.mail.host")).isEqualTo("localhost");
            assertThat(context.getEnvironment().getProperty("spring.mail.properties.mail.smtp.ssl.trust")).isNull();
            assertThat(context.getEnvironment().getProperty("management.endpoints.web.exposure.include"))
                    .isEqualTo("health,info,prometheus");
            JavaMailSenderImpl sender = (JavaMailSenderImpl) context.getBean(JavaMailSender.class);
            assertThat(sender.getHost()).isEqualTo("localhost");
            assertThat(sender.getSession().getProperties().getProperty("mail.smtp.ssl.trust")).isNull();
            assertThat(sender.getSession().getProperties().getProperty("mail.smtp.starttls.required")).isEqualTo("true");

            EmailBrandingProperties branding = context.getBean(EmailBrandingProperties.class);
            // The assets root comes from the environment, never from the file.
            assertThat(branding.assetsBaseUrl()).isEqualTo("https://assets.example.org/tenants");
            assertThat(branding.tenants()).containsOnlyKeys("cgcom", "kpmg");
            assertThat(branding.tenants())
                    .containsEntry("cgcom", new EmailBrandingProperties.TenantEmailBranding(true, true))
                    .containsEntry("kpmg", new EmailBrandingProperties.TenantEmailBranding(true, false));
        });
    }

    @Test
    @DisplayName("F-M1 — brandingFile_missing_failsTheStartNamingTheVariable")
    void brandingFile_missing_failsTheStartNamingTheVariable() {
        String missing = directory.resolve("nope.yaml").toUri().toString();

        shippedConfigWithSmtp(missing).run(context -> assertThat(context).hasFailed().getFailure()
                .rootCause().hasMessageContaining("EMAIL_BRANDING_PATH").hasMessageContaining("cannot be read"));
    }

    @Test
    @DisplayName("F-M1 — brandingFile_notYaml_failsTheStartWithoutQuotingIt")
    void brandingFile_notYaml_failsTheStartWithoutQuotingIt() throws IOException {
        Path file = Files.writeString(directory.resolve("bad.yaml"), "email-branding: [unclosed\n  secret-value: x: y");

        shippedConfigWithSmtp(file.toUri().toString()).run(context -> assertThat(context).hasFailed().getFailure()
                .rootCause().hasMessageContaining("cannot be read").hasMessageNotContaining("secret-value"));
    }

    @Test
    @DisplayName("F-M1 — brandingFile_bundledDefault_hasNoTenants")
    void brandingFile_bundledDefault_hasNoTenants() {
        shippedConfigWithSmtp("classpath:email-branding.yaml").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(EmailBrandingProperties.class).tenants()).isEmpty();
        });
    }
}
