package es.in2.issuanceportal.journey.infrastructure.mail;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How the portal's mail (incidents and verification codes) goes out. There is no default provider on purpose: a
 * service without one does not start. The SMTP server and the sender are Spring's own {@code spring.mail}.
 */
@Validated
@ConfigurationProperties("issuance-portal.mail")
public record MailProperties(@NotNull Provider provider) {

    public enum Provider { SMTP, LOCAL }
}
