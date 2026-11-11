package es.in2.issuanceportal.journey.infrastructure.web;

import es.in2.issuanceportal.journey.application.port.EmailCodeRateLimit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * The limits per client address, each with its own budget: incidents and code emails never spend each
 * other's.
 */
@Configuration
class ClientRateLimitConfiguration {

    @Bean
    IncidentRateLimiter incidentRateLimiter(Clock clock) {
        return new IncidentRateLimiter(clock, IncidentRateLimiter.INCIDENT_LIMIT);
    }

    @Bean
    EmailCodeRateLimit emailCodeRateLimit(Clock clock) {
        IncidentRateLimiter limiter = new IncidentRateLimiter(clock, IncidentRateLimiter.EMAIL_CODE_LIMIT);
        return new EmailCodeRateLimit() {
            @Override
            public Optional<Duration> tryConsume(String clientAddress) {
                return limiter.tryConsume(clientAddress);
            }

            @Override
            public void giveBack(String clientAddress) {
                limiter.giveBack(clientAddress);
            }
        };
    }
}
