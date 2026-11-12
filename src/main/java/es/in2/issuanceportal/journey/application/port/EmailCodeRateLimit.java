package es.in2.issuanceportal.journey.application.port;

import java.time.Duration;
import java.util.Optional;

/** At most so many code emails an hour from each client address (design.md §3.6, AD-15). */
public interface EmailCodeRateLimit {

    /**
     * Counts a send from the address when it is within the limit. Otherwise returns how long until another is
     * allowed, for {@code Retry-After}.
     */
    Optional<Duration> tryConsume(String clientAddress);

    /** Takes back the latest send of the address, which did not go out. */
    void giveBack(String clientAddress);
}
