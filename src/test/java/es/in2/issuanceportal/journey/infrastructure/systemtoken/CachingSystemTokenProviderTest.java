package es.in2.issuanceportal.journey.infrastructure.systemtoken;

import es.in2.issuanceportal.MutableClock;
import es.in2.issuanceportal.journey.application.port.SystemToken;
import es.in2.issuanceportal.journey.application.port.SystemTokenUnavailableException;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CachingSystemTokenProviderTest {

    private static final Instant START = Instant.parse("2026-11-05T10:00:00Z");
    private static final Duration LIFETIME = Duration.ofSeconds(900);
    private static final TenantId SANDBOX = new TenantId("sandbox");
    private static final TenantId KPMG = new TenantId("kpmg");

    private MutableClock clock;
    private final List<TenantId> requested = new ArrayList<>();
    private CachingSystemTokenProvider provider;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(START);
        requested.clear();
        provider = new CachingSystemTokenProvider(this::grant, clock);
    }

    private SystemToken grant(TenantId tenant) {
        synchronized (requested) {
            requested.add(tenant);
            return new SystemToken("token-" + tenant + "-" + requested.size(), clock.instant().plus(LIFETIME));
        }
    }

    @Test
    @DisplayName("EC-05 — a token with more than 30 s left is reused without asking the Verifier")
    void tokenFor_moreThanMarginLeft_reusesToken() {
        SystemToken first = provider.tokenFor(SANDBOX);
        clock.advance(LIFETIME.minusSeconds(31));

        assertThat(provider.tokenFor(SANDBOX)).isEqualTo(first);
        assertThat(requested).hasSize(1);
    }

    @Test
    @DisplayName("EC-05 — with 30 s or less left the next request asks for another token")
    void tokenFor_marginReached_requestsAnotherToken() {
        SystemToken first = provider.tokenFor(SANDBOX);
        clock.advance(LIFETIME.minusSeconds(30));

        SystemToken second = provider.tokenFor(SANDBOX);

        assertThat(second).isNotEqualTo(first);
        assertThat(second.expiresAt()).isEqualTo(clock.instant().plus(LIFETIME));
        assertThat(requested).hasSize(2);
    }

    @Test
    @DisplayName("EC-05 — an invalidated token is asked for again")
    void invalidate_cachedToken_nextCallRequestsAnother() {
        SystemToken first = provider.tokenFor(SANDBOX);

        provider.invalidate(SANDBOX);

        assertThat(provider.tokenFor(SANDBOX)).isNotEqualTo(first);
        assertThat(requested).hasSize(2);
    }

    @Test
    @DisplayName("EC-05 — invalidating a tenant leaves the others' tokens")
    void invalidate_oneTenant_keepsTheOthers() {
        provider.tokenFor(SANDBOX);
        SystemToken kpmg = provider.tokenFor(KPMG);

        provider.invalidate(SANDBOX);

        assertThat(provider.tokenFor(KPMG)).isEqualTo(kpmg);
        assertThat(requested).containsExactly(SANDBOX, KPMG);
    }

    @Test
    @DisplayName("EC-05 — the token of a tenant is never used for another")
    void tokenFor_twoTenants_eachGetsItsOwnToken() {
        SystemToken sandbox = provider.tokenFor(SANDBOX);
        SystemToken kpmg = provider.tokenFor(KPMG);

        assertThat(sandbox.value()).contains("sandbox");
        assertThat(kpmg.value()).contains("kpmg");
        assertThat(provider.tokenFor(SANDBOX)).isEqualTo(sandbox);
        assertThat(provider.tokenFor(KPMG)).isEqualTo(kpmg);
    }

    @Test
    @DisplayName("EC-05 — concurrent requests of a tenant share one call to the Verifier")
    void tokenFor_concurrentRequests_singleCallToVerifier() throws Exception {
        int callers = 20;
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch inFlight = new CountDownLatch(1);
        CachingSystemTokenProvider slow = new CachingSystemTokenProvider(tenant -> {
            calls.incrementAndGet();
            await(inFlight);
            return new SystemToken("shared", clock.instant().plus(LIFETIME));
        }, clock);
        CountDownLatch ready = new CountDownLatch(callers);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<SystemToken>> results = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    return slow.tokenFor(SANDBOX);
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            inFlight.countDown();
            for (Future<SystemToken> result : results) {
                assertThat(result.get(5, TimeUnit.SECONDS).value()).isEqualTo("shared");
            }
        }

        assertThat(calls).hasValue(1);
    }

    @Test
    @DisplayName("ES-05 — a failed request is not cached: the next one asks again")
    void tokenFor_verifierFails_nextCallAsksAgain() {
        AtomicInteger calls = new AtomicInteger();
        CachingSystemTokenProvider flaky = new CachingSystemTokenProvider(tenant -> {
            if (calls.incrementAndGet() == 1) {
                throw new SystemTokenUnavailableException(SystemTokenUnavailableException.Reason.VERIFIER_UNREACHABLE);
            }
            return new SystemToken("second", clock.instant().plus(LIFETIME));
        }, clock);

        assertThatThrownBy(() -> flaky.tokenFor(SANDBOX)).isInstanceOf(SystemTokenUnavailableException.class);

        assertThat(flaky.tokenFor(SANDBOX).value()).isEqualTo("second");
    }

    @Test
    @DisplayName("NFR-3 — the token does not show in toString")
    void toString_systemToken_doesNotShowTheToken() {
        assertThat(new SystemToken("secret-value", START).toString()).doesNotContain("secret-value");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }
}
