package es.in2.issuanceportal.authenticsource.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import es.in2.issuanceportal.authenticsource.application.port.AuthenticSourceConfigRepository;
import es.in2.issuanceportal.authenticsource.domain.config.AuthenticSourceConfig;
import es.in2.issuanceportal.authenticsource.domain.config.InvalidSourceConfigException;
import es.in2.issuanceportal.shared.tenant.TenantId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * The module's part of {@code portal_config} in the tenant's schema, cached like the rest of the
 * configuration (design.md AD-7). A missing or invalid configuration is never cached, so a fixed row is
 * seen at once; it is logged by field, never by value.
 */
@Slf4j
@Repository
class JdbcAuthenticSourceConfigRepository implements AuthenticSourceConfigRepository {

    static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Cache<TenantId, AuthenticSourceConfig> byTenant;

    JdbcAuthenticSourceConfigRepository(JdbcClient jdbc, ObjectMapper json, Clock clock,
                                        @Value("${issuance-portal.portal-config.cache-ttl:5m}") Duration cacheTtl) {
        this.jdbc = jdbc;
        this.json = json;
        this.byTenant = Caffeine.newBuilder()
                .expireAfterWrite(cacheTtl)
                .maximumSize(1_000)
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                .build();
    }

    @Override
    public Optional<AuthenticSourceConfig> find(TenantId tenant) {
        AuthenticSourceConfig cached = byTenant.getIfPresent(tenant);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<AuthenticSourceConfig> loaded = load(tenant);
        loaded.ifPresent(config -> byTenant.put(tenant, config));
        return loaded;
    }

    private Optional<AuthenticSourceConfig> load(TenantId tenant) {
        return jdbc.sql("SELECT config::text FROM " + tenant.schema() + ".portal_config WHERE tenant = :tenant")
                .param("tenant", tenant.value())
                .query(String.class)
                .optional()
                .flatMap(document -> parse(tenant, document));
    }

    private Optional<AuthenticSourceConfig> parse(TenantId tenant, String document) {
        try {
            AuthenticSourceConfigDocument parsed = json.readValue(document, AuthenticSourceConfigDocument.class);
            if (parsed == null) {
                log.warn("Authentic source configuration of tenant {} is empty", tenant);
                return Optional.empty();
            }
            return Optional.of(parsed.toDomain());
        } catch (InvalidSourceConfigException e) {
            log.warn("Authentic source configuration of tenant {} is invalid: {}", tenant, e.getMessage());
        } catch (JsonMappingException e) {
            log.warn("Authentic source configuration of tenant {} is invalid: wrong type at {}", tenant, path(e));
        } catch (JsonProcessingException _) {
            log.warn("Authentic source configuration of tenant {} is not valid JSON", tenant);
        }
        return Optional.empty();
    }

    private static String path(JsonMappingException e) {
        return e.getPath().stream()
                .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "[" + ref.getIndex() + "]")
                .collect(Collectors.joining("."));
    }
}
