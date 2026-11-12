package es.in2.issuanceportal.journey.infrastructure.mail;

import es.in2.issuanceportal.journey.application.port.EmailCodeMessage;
import es.in2.issuanceportal.journey.domain.EmailVerification;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Renders the HTML of the code email: the wallet's OTP email template, without links, with the tenant's
 * branding and the texts of {@code email-messages*.properties} in the language of the message (Spanish when the
 * bundle has none).
 */
class EmailCodeHtml {

    private static final String TEMPLATE = "verification-code-email";

    private final SpringTemplateEngine engine = engine();
    private final EmailBrandingResolver branding;

    EmailCodeHtml(EmailBrandingResolver branding) {
        this.branding = branding;
    }

    String render(EmailCodeMessage message) {
        Context context = new Context(Locale.forLanguageTag(message.language().code()));
        context.setVariable("subject", message.subject());
        context.setVariable("txCode", message.code());
        context.setVariable("expirationMinutes", EmailVerification.CODE_LIFETIME.toMinutes());
        context.setVariable("branding", branding.resolve(message.tenant()));
        context.setVariable("tenantDisplayName", message.portalName());
        return engine.process(TEMPLATE, context);
    }

    private static SpringTemplateEngine engine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("mail-templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setCacheable(true);

        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("email-messages");
        messages.setDefaultEncoding(StandardCharsets.UTF_8.name());
        // Spanish is the default bundle: the language of the server must not pick another.
        messages.setFallbackToSystemLocale(false);

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setTemplateEngineMessageSource(messages);
        return engine;
    }
}
