package es.in2.issuanceportal.session.infrastructure.crypto;

import es.in2.issuanceportal.session.application.port.PersonalDataCipher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.kms.KmsClient;

import java.time.Clock;
import java.util.Map;
import java.util.Set;

@Slf4j
@Configuration
class CryptoConfiguration {

    /** Environments where the local key, published in git, may encrypt personal data. */
    static final Set<String> LOCAL_KEY_ENVIRONMENTS = Set.of("local", "test");
    /** Set by ECS in every task: a service that sees it runs in AWS, whatever PORTAL_ENV says. */
    static final String ECS_METADATA = "ECS_CONTAINER_METADATA_URI_V4";

    @Bean
    PersonalDataCipher personalDataCipher(CryptoProperties properties, Clock clock,
                                          @Value("${issuance-portal.env}") String env) {
        requireLocalKeyAllowed(properties.provider(), env, System.getenv());
        log.info("Personal data encrypted with the {} data key provider (env {})", properties.provider(), env);
        DataKeyProvider keys = switch (properties.provider()) {
            // Region and credentials come from the ECS task environment.
            case KMS -> new KmsDataKeyProvider(KmsClient.create(), properties.kmsKeyId(),
                    properties.dataKeyRotation(), clock);
            case LOCAL -> new LocalDataKeyProvider(properties.localKey());
        };
        return new EnvelopeCipher(keys);
    }

    /**
     * The local key is public. Outside a local or test environment, or anywhere on ECS, the service
     * refuses to start with it rather than encrypt the holders' identity with a key anyone can read.
     */
    static void requireLocalKeyAllowed(CryptoProperties.Provider provider, String env, Map<String, String> environment) {
        if (provider != CryptoProperties.Provider.LOCAL) {
            return;
        }
        if (!LOCAL_KEY_ENVIRONMENTS.contains(env)) {
            throw new IllegalStateException("The local data key is only allowed in " + LOCAL_KEY_ENVIRONMENTS
                    + ", not in '" + env + "'. Use the KMS provider.");
        }
        if (environment.containsKey(ECS_METADATA)) {
            throw new IllegalStateException("The local data key is not allowed on ECS. Use the KMS provider.");
        }
    }
}
