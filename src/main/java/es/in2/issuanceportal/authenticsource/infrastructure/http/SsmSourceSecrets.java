package es.in2.issuanceportal.authenticsource.infrastructure.http;

import es.in2.issuanceportal.authenticsource.application.port.SourceSecrets;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The credentials in SSM Parameter Store as {@code SecureString}, read with decryption at the time of use and
 * kept in memory for at most {@code ttl} (design.md AD-4). The client is created on first use, so a service
 * whose tenants use no secret never needs AWS.
 */
final class SsmSourceSecrets implements SourceSecrets {

    private record Cached(String value, Instant expiresAt) {

        @Override
        public String toString() {
            return "Cached[expiresAt=" + expiresAt + "]";
        }
    }

    private final Supplier<SsmClient> clientFactory;
    private final Clock clock;
    private final Duration ttl;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private volatile SsmClient client;

    SsmSourceSecrets(Supplier<SsmClient> clientFactory, Clock clock, Duration ttl) {
        this.clientFactory = clientFactory;
        this.clock = clock;
        this.ttl = ttl;
    }

    @Override
    public String secret(String reference) {
        Cached cached = cache.get(reference);
        if (cached != null && clock.instant().isBefore(cached.expiresAt())) {
            return cached.value();
        }
        String value;
        try {
            value = ssm().getParameter(request -> request.name(reference).withDecryption(true)).parameter().value();
        } catch (SdkException _) {
            throw new SourceSecretUnavailableException();
        }
        if (value == null || value.isBlank()) {
            throw new SourceSecretUnavailableException();
        }
        cache.put(reference, new Cached(value, clock.instant().plus(ttl)));
        return value;
    }

    private SsmClient ssm() {
        SsmClient current = client;
        if (current == null) {
            synchronized (this) {
                if (client == null) {
                    client = clientFactory.get();
                }
                current = client;
            }
        }
        return current;
    }
}
