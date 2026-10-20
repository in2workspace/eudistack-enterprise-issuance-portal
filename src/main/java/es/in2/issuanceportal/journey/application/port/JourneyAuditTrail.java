package es.in2.issuanceportal.journey.application.port;

import es.in2.issuanceportal.journey.domain.JourneyState;
import es.in2.issuanceportal.journey.domain.JourneyStep;
import es.in2.issuanceportal.journey.domain.WithdrawalReason;
import es.in2.issuanceportal.shared.tenant.TenantId;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Trace of every change of a journey (prd.md FR-13, design.md §3.2). Events carry identifiers, states
 * and steps only: never identity, source data, email or attestation data.
 */
public interface JourneyAuditTrail {

    enum Kind { STARTED, STEP_REACHED, COMPLETED, WITHDRAWN }

    record Event(
            UUID eventId,
            Kind kind,
            UUID journeyId,
            JourneyState state,
            JourneyStep step,
            Optional<WithdrawalReason> withdrawalReason,
            Instant occurredAt) {
    }

    void append(TenantId tenant, Event event);
}
