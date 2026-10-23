package es.in2.issuanceportal.journey.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import es.in2.issuanceportal.journey.application.port.PortalConfigRepository;
import es.in2.issuanceportal.journey.domain.config.InvalidPortalConfigException;
import es.in2.issuanceportal.journey.domain.config.PortalConfig;
import es.in2.issuanceportal.shared.tenant.TenantId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * {@code portal_config} in the tenant's schema. A valid configuration is cached for 5 minutes (design.md
 * AD-7), so an operator's change shows within that time without a deployment. A missing or invalid one is
 * never cached: once the tenant's row is loaded or fixed, the next request sees it.
 */
@Slf4j
@Repository
class JdbcPortalConfigRepository implements PortalConfigRepository {

    static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Cache<TenantId, PortalConfig> byTenant;

    JdbcPortalConfigRepository(JdbcClient jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.byTenant = Caffeine.newBuilder()
                .expireAfterWrite(CACHE_TTL)
                .maximumSize(1_000)
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                .build();
    }

    @Override
    public Optional<PortalConfig> find(TenantId tenant) {
        PortalConfig cached = byTenant.getIfPresent(tenant);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<PortalConfig> loaded = load(tenant);
        loaded.ifPresent(config -> byTenant.put(tenant, config));
        return loaded;
    }

    private Optional<PortalConfig> load(TenantId tenant) {
        return jdbc.sql("SELECT config::text FROM " + tenant.schema() + ".portal_config WHERE tenant = :tenant")
                .param("tenant", tenant.value())
                .query(String.class)
                .optional()
                .flatMap(document -> parse(tenant, document));
    }

    /** Logs which field is wrong, never a value: the message of a mapping error may quote the input. */
    private Optional<PortalConfig> parse(TenantId tenant, String document) {
        try {
            PortalConfigDocument parsed = json.readValue(document, PortalConfigDocument.class);
            if (parsed == null) {
                log.warn("Portal configuration of tenant {} is empty", tenant);
                return Optional.empty();
            }
            return Optional.of(parsed.toDomain());
        } catch (InvalidPortalConfigException e) {
            log.warn("Portal configuration of tenant {} is invalid: {}", tenant, e.getMessage());
        } catch (JsonMappingException e) {
            log.warn("Portal configuration of tenant {} is invalid: wrong type at {}", tenant, path(e));
        } catch (JsonProcessingException e) {
            log.warn("Portal configuration of tenant {} is not valid JSON", tenant);
        }
        return Optional.empty();
    }

    private static String path(JsonMappingException e) {
        return e.getPath().stream()
                .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "[" + ref.getIndex() + "]")
                .collect(Collectors.joining("."));
    }
}
