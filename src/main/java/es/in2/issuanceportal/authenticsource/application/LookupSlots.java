package es.in2.issuanceportal.authenticsource.application;

import es.in2.issuanceportal.shared.tenant.TenantId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Searches of the authentic source in flight in this instance, including those the module stopped waiting
 * for. A cap per tenant keeps a slow source, or one tenant's holders, from taking the slots of the others;
 * the cap of the instance bounds the threads and the calls to the sources.
 */
@Component
class LookupSlots {

    private final Semaphore instance;
    private final int perTenant;
    /** One entry per tenant that has searched: tenants come from the tenant registry, so it stays small. */
    private final Map<TenantId, Semaphore> tenants = new ConcurrentHashMap<>();

    LookupSlots(@Value("${issuance-portal.authentic-source.max-concurrent-lookups:100}") int perInstance,
                @Value("${issuance-portal.authentic-source.max-concurrent-lookups-per-tenant:20}") int perTenant) {
        if (perInstance < 1 || perTenant < 1) {
            throw new IllegalArgumentException("issuance-portal.authentic-source.max-concurrent-lookups"
                    + " and max-concurrent-lookups-per-tenant must be at least 1");
        }
        this.instance = new Semaphore(perInstance);
        this.perTenant = perTenant;
    }

    /** A slot for a search of the tenant, or empty when the tenant or the instance has none free. */
    Optional<Slot> tryAcquire(TenantId tenant) {
        Semaphore own = tenants.computeIfAbsent(tenant, t -> new Semaphore(perTenant));
        if (!own.tryAcquire()) {
            return Optional.empty();
        }
        if (!instance.tryAcquire()) {
            own.release();
            return Optional.empty();
        }
        return Optional.of(new Slot(own));
    }

    /**
     * A held slot. The search claims it when it starts and gives it back when it ends; a search cancelled
     * before it starts never runs, so whoever stopped waiting for it gives it back instead. Never twice.
     */
    final class Slot {

        private final Semaphore own;
        private final AtomicBoolean claimed = new AtomicBoolean();
        private final AtomicBoolean released = new AtomicBoolean();

        private Slot(Semaphore own) {
            this.own = own;
        }

        /** Called by the search when it starts: false if the slot was already given back, and it must not run. */
        boolean start() {
            return claimed.compareAndSet(false, true);
        }

        /** Called by whoever stopped waiting: gives the slot back only if the search never started. */
        void abandon() {
            if (claimed.compareAndSet(false, true)) {
                release();
            }
        }

        void release() {
            if (released.compareAndSet(false, true)) {
                own.release();
                instance.release();
            }
        }
    }
}
