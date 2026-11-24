package es.in2.issuanceportal.authenticsource.infrastructure.http;

import es.in2.issuanceportal.authenticsource.application.port.SourceSecrets;
import es.in2.issuanceportal.shared.tenant.TenantId;
import lombok.extern.slf4j.Slf4j;
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
 *
 * <p>A reference is read only if it is under the tenant's prefix, {@code prefixTemplate} with {@code {tenant}}
 * replaced: the service's role can read other parameters too (the machine credentials of every tenant), and the
 * tenant's configuration must not be able to send any of them to its source. Without a template no secret is read.
 */
@Slf4j
final class SsmSourceSecrets implements SourceSecrets {

    private record Cached(String value, Instant expiresAt) {

        @Override
        public String toString() {
            return "Cached[expiresAt=" + expiresAt + "]";
        }
    }

    static final String TENANT_PLACEHOLDER = "{tenant}";

    private final Supplier<SsmClient> clientFactory;
    private final Clock clock;
    private final Duration ttl;
    private final String prefixTemplate;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private volatile SsmClient client;

    SsmSourceSecrets(Supplier<SsmClient> clientFactory, Clock clock, Duration ttl, String prefixTemplate) {
        this.clientFactory = clientFactory;
        this.clock = clock;
        this.ttl = ttl;
        this.prefixTemplate = prefixTemplate == null ? "" : prefixTemplate.strip();
    }

    @Override
    public String secret(TenantId tenant, String reference) {
        if (!withinPrefix(tenant, reference)) {
            // Never the reference: it may name another tenant's parameter.
            log.warn("Source secret of tenant {} is outside its SSM prefix, not read", tenant);
            throw new SourceSecretUnavailableException();
        }
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

    private boolean withinPrefix(TenantId tenant, String reference) {
        if (!prefixTemplate.contains(TENANT_PLACEHOLDER) || reference == null) {
            return false;
        }
        String prefix = prefixTemplate.replace(TENANT_PLACEHOLDER, tenant.value());
        prefix = prefix.endsWith("/") ? prefix : prefix + "/";
        return reference.startsWith(prefix) && reference.length() > prefix.length()
                && !reference.contains("..") && !reference.contains("//");
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
