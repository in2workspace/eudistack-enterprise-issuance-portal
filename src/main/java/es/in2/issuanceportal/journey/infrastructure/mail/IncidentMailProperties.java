package es.in2.issuanceportal.journey.infrastructure.mail;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How incidents are sent. There is no default provider on purpose: a service without one does not start.
 *
 * @param from sender address, on the platform's verified SES identity
 */
@Validated
@ConfigurationProperties("issuance-portal.incidents.mail")
public record IncidentMailProperties(@NotNull Provider provider, @NotBlank @Email String from) {

    public enum Provider { SES, LOCAL }
}
