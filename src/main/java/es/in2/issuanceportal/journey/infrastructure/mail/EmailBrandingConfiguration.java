package es.in2.issuanceportal.journey.infrastructure.mail;

import es.in2.issuanceportal.journey.infrastructure.mail.EmailBrandingProperties.TenantEmailBranding;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Reads the email branding file and binds only its {@code email-branding.tenants} keys. The file is not a Spring
 * configuration source: nothing else in it (a {@code spring.mail} property, say) reaches the environment, so
 * whoever can replace it cannot weaken the service's settings.
 */
@Configuration
class EmailBrandingConfiguration {

    private static final String TENANTS = "email-branding.tenants";

    @Bean
    EmailBrandingProperties emailBrandingProperties(ResourceLoader resources,
            @Value("${EMAIL_BRANDING_PATH:classpath:email-branding.yaml}") String location,
            @Value("${email-branding.assets-base-url:}") String assetsBaseUrl) {
        return new EmailBrandingProperties(assetsBaseUrl, tenants(resources.getResource(location), location));
    }

    static Map<String, TenantEmailBranding> tenants(Resource file, String location) {
        List<PropertySource<?>> sources;
        try {
            if (!file.isReadable()) {
                throw new IOException("not readable");
            }
            sources = new YamlPropertySourceLoader().load("email-branding", file);
        } catch (IOException | RuntimeException e) {
            // Only the location and the kind of error: the file is not meant to be quoted.
            throw new IllegalStateException("EMAIL_BRANDING_PATH " + location + " cannot be read as YAML ("
                    + e.getClass().getSimpleName() + ")");
        }
        return new Binder(ConfigurationPropertySources.from(sources))
                .bind(TENANTS, Bindable.mapOf(String.class, TenantEmailBranding.class))
                .orElse(Map.of());
    }
}
