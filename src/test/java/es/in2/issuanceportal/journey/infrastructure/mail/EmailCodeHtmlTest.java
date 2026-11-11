package es.in2.issuanceportal.journey.infrastructure.mail;

import es.in2.issuanceportal.journey.application.port.EmailCodeMessage;
import es.in2.issuanceportal.journey.domain.EmailLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Map;

import static es.in2.issuanceportal.journey.infrastructure.mail.SmtpTestSupport.ASSETS;
import static org.assertj.core.api.Assertions.assertThat;

/** Renders the real template with the real message bundles, outside the mail server. */
class EmailCodeHtmlTest {

    @Test
    @DisplayName("AC-01 — render_tenantWithoutBranding_showsGreetingCodeBoxAndHintWithExpiry")
    void render_tenantWithoutBranding_showsGreetingCodeBoxAndHintWithExpiry() {
        String html = SmtpTestSupport.html().render(
                EmailCodeMessage.of("ana@example.org", "482193", "Portal de emisión", EmailLanguage.ES, "cgcom"));

        assertThat(html)
                .doesNotContain("??")
                .contains("<title>Tu código de verificación — Portal de emisión</title>")
                .contains("lang=\"es\"")
                .contains("Hola,")
                .contains("Tu código de verificación")
                .contains("background-color: #EBF0F7; border: 1px solid #8EACE1")
                .contains(">482193</span>")
                .contains("Introdúcelo en el portal de emisión. Caduca en 10 minutos.")
                .contains("Si no has pedido este código, puedes ignorar este correo.")
                .doesNotContain("fonts.googleapis.com", "<link", "href=")
                .doesNotContain("<img")
                .doesNotContain("background-image")
                .doesNotContain("cid:");
    }

    @Test
    @DisplayName("AC-01 — render_english_usesTheEnglishBundle")
    void render_english_usesTheEnglishBundle() {
        String html = SmtpTestSupport.html().render(
                EmailCodeMessage.of("ana@example.org", "482193", "Issuance portal", EmailLanguage.EN, "cgcom"));

        assertThat(html)
                .doesNotContain("??")
                .contains("<title>Your verification code — Issuance portal</title>")
                .contains("lang=\"en\"")
                .contains("Hello,")
                .contains("Enter this code in the issuance portal. It expires in 10 minutes.")
                .contains("If you did not request this code, you can safely ignore this email.");
    }

    @Test
    @DisplayName("AC-01 — render_tenantWithBranding_showsLogoWithThePortalNameAndHeaderBackground")
    void render_tenantWithBranding_showsLogoWithThePortalNameAndHeaderBackground() {
        String html = SmtpTestSupport.html(Map.of("cgcom", new EmailBrandingProperties.TenantEmailBranding(true, true)))
                .render(EmailCodeMessage.of("ana@example.org", "482193", "Portal DoctorID", EmailLanguage.ES,
                        "cgcom"));

        assertThat(html)
                .contains("src=\"" + ASSETS + "/cgcom/email/logo.png\"")
                .contains("alt=\"Portal DoctorID\"")
                .contains("background-image: url(" + ASSETS + "/cgcom/email/header-bg.png)")
                .doesNotContainPattern("<a[\\s>]");
    }

    @Test
    @DisplayName("AC-01 — render_portalNameWithMarkup_isEscaped")
    void render_portalNameWithMarkup_isEscaped() {
        String html = SmtpTestSupport.html().render(
                EmailCodeMessage.of("ana@example.org", "482193", "<b>X</b>", EmailLanguage.ES, "cgcom"));

        assertThat(html).doesNotContain("<b>X</b>").contains("&lt;b&gt;X&lt;/b&gt;");
    }

    @Test
    @DisplayName("AC-01 — properties_sharedBrandingFile_bindsTheImageFlagsAndIgnoresTheRest")
    void properties_sharedBrandingFile_bindsTheImageFlagsAndIgnoresTheRest() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(Binding.class)
                .withPropertyValues("email-branding.assets-base-url=" + ASSETS,
                        "email-branding.tenants.dome.logo=true",
                        "email-branding.tenants.dome.header-background=true",
                        "email-branding.tenants.dome.social-links[0].type=github",
                        "email-branding.tenants.dome.social-links[0].url=https://github.com/dome",
                        "email-branding.tenants.dome.support.url=https://example.org")
                .run(context -> {
                    EmailBrandingProperties properties = context.getBean(EmailBrandingProperties.class);
                    assertThat(properties.assetsBaseUrl()).isEqualTo(ASSETS);
                    assertThat(properties.tenants().get("dome"))
                            .isEqualTo(new EmailBrandingProperties.TenantEmailBranding(true, true));
                });
    }

    @EnableConfigurationProperties(EmailBrandingProperties.class)
    static class Binding {
    }
}
