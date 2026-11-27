package es.in2.issuanceportal.journey.application.port;

import java.time.Duration;
import java.util.Optional;

/**
 * At most so many checks of the email code an hour from each client address (design.md §3.6 step 1, AD-15). Its
 * budget is its own: it neither spends nor is spent by the one of the code emails.
 */
public interface EmailCodeCheckRateLimit {

    /**
     * Counts a check from the address when it is within the limit, whatever its result: nothing is given back.
     * Otherwise returns how long until another is allowed, for {@code Retry-After}.
     */
    Optional<Duration> tryConsume(String clientAddress);
}
