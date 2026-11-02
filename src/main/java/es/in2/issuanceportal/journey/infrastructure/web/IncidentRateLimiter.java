package es.in2.issuanceportal.journey.infrastructure.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * At most {@value #LIMIT} incidents in any hour from each client (design.md AD-8, ac.md AC-06): a sliding
 * window over the times of the last accepted incidents, so a sixth within an hour of the oldest is always
 * rejected, also across the turn of the hour. Bucket4j cannot give that guarantee (a fixed window allows
 * ten around its boundary), which is why the window is kept here. The windows live in memory, so the limit
 * applies per instance of the service.
 *
 * <p>An IPv6 client counts by its /64, the prefix a single host usually holds; an IPv4-mapped address
 * counts as its IPv4 address.
 */
@Component
class IncidentRateLimiter {

    static final int LIMIT = 5;
    static final Duration WINDOW = Duration.ofHours(1);

    private final Clock clock;
    private final Cache<String, Deque<Instant>> byClient;
    /** Clients already logged as over the limit in the current window: one log line each, not one per request. */
    private final Cache<String, Boolean> reported;

    IncidentRateLimiter(Clock clock) {
        this.clock = clock;
        this.byClient = Caffeine.newBuilder()
                .expireAfterAccess(WINDOW)
                .maximumSize(100_000)
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                .build();
        this.reported = Caffeine.newBuilder()
                .expireAfterWrite(WINDOW)
                .maximumSize(100_000)
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                .build();
    }

    /**
     * Records an incident from the address when it is within the limit. Otherwise returns how long until
     * the oldest incident in the window leaves it, for {@code Retry-After}.
     */
    Optional<Duration> tryConsume(String address) {
        Deque<Instant> accepted = byClient.get(clientKey(address), key -> new ArrayDeque<>(LIMIT));
        synchronized (accepted) {
            Instant now = clock.instant();
            Instant windowStart = now.minus(WINDOW);
            while (!accepted.isEmpty() && !accepted.peekFirst().isAfter(windowStart)) {
                accepted.pollFirst();
            }
            if (accepted.size() < LIMIT) {
                accepted.addLast(now);
                return Optional.empty();
            }
            return Optional.of(Duration.between(windowStart, accepted.peekFirst()));
        }
    }

    /**
     * Takes back the client's latest incident when it could not be delivered: only incidents that reach
     * support count, so a holder whose sends failed can retry what they wrote.
     */
    void giveBack(String address) {
        Deque<Instant> accepted = byClient.getIfPresent(clientKey(address));
        if (accepted != null) {
            synchronized (accepted) {
                accepted.pollLast();
            }
        }
    }

    /** True only for the first rejection of the client in the window, which is the one worth a log line. */
    boolean firstRejectionInWindow(String address) {
        return reported.asMap().putIfAbsent(clientKey(address), Boolean.TRUE) == null;
    }

    /** The address as the limit counts it: IPv4 as is, IPv6 by its /64. */
    static String clientKey(String address) {
        try {
            byte[] bytes = InetAddress.ofLiteral(address).getAddress();
            if (bytes.length == 4) {
                return InetAddress.getByAddress(bytes).getHostAddress();
            }
            byte[] prefix = new byte[16];
            System.arraycopy(bytes, 0, prefix, 0, 8);
            return InetAddress.getByAddress(prefix).getHostAddress() + "/64";
        } catch (IllegalArgumentException | UnknownHostException _) {
            return address;
        }
    }
}
