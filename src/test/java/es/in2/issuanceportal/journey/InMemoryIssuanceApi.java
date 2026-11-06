package es.in2.issuanceportal.journey;

import es.in2.issuanceportal.journey.application.port.IssuanceApi;
import es.in2.issuanceportal.journey.application.port.IssuanceRequest;
import es.in2.issuanceportal.journey.application.port.IssuanceResult;
import es.in2.issuanceportal.shared.tenant.TenantId;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An {@link IssuanceApi} for the tests of the use cases that request an issuance. Like the Issuer, it answers
 * the same process and offer to a repeated idempotency key, and an offer lasts ten minutes, unless the API was
 * told to fail. Safe to call from several threads.
 */
public final class InMemoryIssuanceApi implements IssuanceApi {

    /** One call to {@link #issue}. */
    public record Call(TenantId tenant, UUID idempotencyKey, IssuanceRequest request) {
    }

    private final Clock clock;
    private final List<Call> calls = new ArrayList<>();
    private final Map<UUID, IssuanceResult> issued = new HashMap<>();
    private RuntimeException failure;

    public InMemoryIssuanceApi(Clock clock) {
        this.clock = clock;
    }

    /** From now on, {@link #issue} throws this. */
    public void failWith(RuntimeException exception) {
        this.failure = exception;
    }

    /** From now on, {@link #issue} answers again. */
    public void recover() {
        this.failure = null;
    }

    @Override
    public synchronized IssuanceResult issue(TenantId tenant, UUID idempotencyKey, IssuanceRequest request) {
        calls.add(new Call(tenant, idempotencyKey, request));
        if (failure != null) {
            throw failure;
        }
        return issued.computeIfAbsent(idempotencyKey, key -> new IssuanceResult(UUID.randomUUID(),
                "openid-credential-offer://?credential_offer_uri=https%3A%2F%2Fissuer.test%2Foffers%2F" + issued.size(),
                clock.instant().plus(Duration.ofMinutes(10))));
    }

    public synchronized List<Call> calls() {
        return List.copyOf(calls);
    }

    /** The processes created: one per idempotency key. */
    public synchronized int processes() {
        return issued.size();
    }
}
