package es.in2.issuanceportal.session.infrastructure.crypto;

import es.in2.issuanceportal.session.application.port.PersonalDataCipher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.kms.KmsClient;

import java.time.Clock;

@Configuration
class CryptoConfiguration {

    @Bean
    PersonalDataCipher personalDataCipher(CryptoProperties properties, Clock clock) {
        DataKeyProvider keys = switch (properties.provider()) {
            // Region and credentials come from the ECS task environment.
            case KMS -> new KmsDataKeyProvider(KmsClient.create(), properties.kmsKeyId(),
                    properties.dataKeyRotation(), clock);
            case LOCAL -> new LocalDataKeyProvider(properties.localKey());
        };
        return new EnvelopeCipher(keys);
    }
}
