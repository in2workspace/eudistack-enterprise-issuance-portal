package es.in2.issuanceportal.session.infrastructure.crypto;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * How the personal data of the session is encrypted. There is no default provider on purpose: a
 * service without one does not start.
 */
@Validated
@ConfigurationProperties("issuance-portal.crypto")
public record CryptoProperties(
        @NotNull Provider provider,
        String kmsKeyId,
        String localKey,
        @DefaultValue("PT24H") Duration dataKeyRotation) {

    public enum Provider { KMS, LOCAL }
}
