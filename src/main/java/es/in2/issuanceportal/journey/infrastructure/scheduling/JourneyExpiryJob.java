package es.in2.issuanceportal.journey.infrastructure.scheduling;

import es.in2.issuanceportal.journey.application.JourneyService;
import es.in2.issuanceportal.shared.tenant.TenantId;
import es.in2.issuanceportal.shared.tenant.TenantSchemas;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every minute, closes the sessions inactive for 15 minutes in every tenant with a schema, active or
 * not: a deactivated tenant must not keep personal data either (design.md AD-4).
 * Each tenant has its own ShedLock lock in its schema, so with several instances each tenant is swept
 * by one of them only. A failing tenant does not stop the others.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "issuance-portal.expiry.enabled", havingValue = "true", matchIfMissing = true)
public class JourneyExpiryJob {

    static final String LOCK_NAME = "journey-expiry";
    /** Released before the next run even if an instance dies holding it. */
    private static final Duration LOCK_AT_MOST = Duration.ofSeconds(50);
    /** Held long enough that an instance whose clock is a few seconds behind does not sweep again. */
    private static final Duration LOCK_AT_LEAST = Duration.ofSeconds(30);

    private final JourneyService journeys;
    private final TenantSchemas tenants;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final Map<TenantId, LockingTaskExecutor> executors = new ConcurrentHashMap<>();

    public JourneyExpiryJob(JourneyService journeys, TenantSchemas tenants, JdbcTemplate jdbcTemplate, Clock clock) {
        this.journeys = journeys;
        this.tenants = tenants;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Scheduled(cron = "0 * * * * *")
    public void run() {
        for (TenantId tenant : tenants.withSchema()) {
            try {
                sweep(tenant);
            } catch (RuntimeException e) {
                log.error("Journey expiry failed for tenant {}", tenant, e);
            }
        }
    }

    /** Sweeps the tenant if this instance gets its lock. False when another instance holds it. */
    boolean sweep(TenantId tenant) {
        LockConfiguration lock = new LockConfiguration(clock.instant(), LOCK_NAME, LOCK_AT_MOST, LOCK_AT_LEAST);
        LockingTaskExecutor.TaskResult<Integer> result;
        try {
            result = executor(tenant).executeWithLock(() -> journeys.expireInactive(tenant), lock);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("Journey expiry failed", e);
        }
        if (result.wasExecuted() && result.getResult() > 0) {
            log.info("Closed {} inactive journey sessions for tenant {}", result.getResult(), tenant);
        }
        return result.wasExecuted();
    }

    private LockingTaskExecutor executor(TenantId tenant) {
        return executors.computeIfAbsent(tenant, t -> new DefaultLockingTaskExecutor(new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(jdbcTemplate)
                        .withTableName(t.schema() + ".shedlock")
                        .usingDbTime()
                        .build())));
    }
}
