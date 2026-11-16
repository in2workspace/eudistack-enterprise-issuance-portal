package es.in2.issuanceportal.authenticsource.application.port;

import es.in2.issuanceportal.authenticsource.domain.IdentifierType;
import es.in2.issuanceportal.authenticsource.domain.NotConfirmedReason;
import es.in2.issuanceportal.authenticsource.domain.RequestStatus;
import es.in2.issuanceportal.shared.tenant.TenantId;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Trace of every entitlement check (prd.md FR-07, design.md §3.6). Events carry identifiers, codes and
 * counts only: never the identity, the source's references nor anything the source returned.
 *
 * <p>{@code requestsByStatus} is how many requests the check ended with in each status; a status without
 * requests is absent, and so is the whole map when no request was checked.
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
            Map<RequestStatus, Integer> requestsByStatus,
            Duration duration,
            Instant occurredAt) {
    }

    /** How the notice of data not correct ended (design.md §3.6). */
    enum DisputeResult {
        SENT("sent"), NOT_SUPPORTED("not_supported"), FAILED("failed");

        private final String code;

        DisputeResult(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** One notice of data not correct: no identity, no reference of the request, no data. */
    record DisputeEvent(
            UUID eventId,
            UUID journeyId,
            String attestationType,
            String adapter,
            DisputeResult result,
            Duration duration,
            Instant occurredAt) {
    }

    void append(TenantId tenant, Event event);

    void appendDispute(TenantId tenant, DisputeEvent event);
}
