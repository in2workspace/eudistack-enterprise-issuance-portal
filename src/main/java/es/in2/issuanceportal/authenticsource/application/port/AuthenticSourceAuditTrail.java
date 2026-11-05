package es.in2.issuanceportal.authenticsource.application.port;

import es.in2.issuanceportal.authenticsource.domain.IdentifierType;
import es.in2.issuanceportal.authenticsource.domain.NotConfirmedReason;
import es.in2.issuanceportal.shared.tenant.TenantId;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Trace of every entitlement check (prd.md FR-07, design.md §3.4). Events carry identifiers and codes
 * only: never the personal identifier nor anything the source returned.
 */
public interface AuthenticSourceAuditTrail {

    /** Name recorded as the adapter when the attestation type has no source. */
    String NO_ADAPTER = "none";

    enum Result {
        CONFIRMED("confirmed"), NOT_CONFIRMED("not_confirmed"), UNAVAILABLE("unavailable");

        private final String code;

        Result(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    record Event(
            UUID eventId,
            UUID journeyId,
            String attestationType,
            String adapter,
            IdentifierType identifierType,
            Result result,
            Optional<NotConfirmedReason> reason,
            Duration duration,
            Instant occurredAt) {
    }

    void append(TenantId tenant, Event event);
}
