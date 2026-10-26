package es.in2.issuanceportal.journey.infrastructure.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.TimeMeter;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * At most {@value #LIMIT} incidents per hour from each IP address (design.md AD-8). The buckets live in
 * memory, so the limit applies per instance of the service.
 */
@Component
class IncidentRateLimiter {

    static final int LIMIT = 5;
    static final Duration WINDOW = Duration.ofHours(1);

    private final Cache<String, Bucket> byIp;
    private final TimeMeter time;

    IncidentRateLimiter(Clock clock) {
        this.time = new ClockTimeMeter(clock);
        this.byIp = Caffeine.newBuilder()
                .expireAfterAccess(WINDOW)
                .maximumSize(100_000)
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                .build();
    }

    /** False when the address has already sent {@value #LIMIT} incidents in the current hour. */
    boolean tryConsume(String ip) {
        return byIp.get(ip, key -> newBucket()).tryConsume(1);
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(LIMIT).refillIntervally(LIMIT, WINDOW).build())
                .withCustomTimePrecision(time)
                .build();
    }

    /** Bucket4j reads time from the injected clock, like the rest of the service. */
    private record ClockTimeMeter(Clock clock) implements TimeMeter {

        @Override
        public long currentTimeNanos() {
            return TimeUnit.MILLISECONDS.toNanos(clock.millis());
        }

        @Override
        public boolean isWallClockBased() {
            return true;
        }
    }
}
