package es.in2.issuanceportal.journey.application.port;

import es.in2.issuanceportal.journey.domain.incident.IncidentType;
import es.in2.issuanceportal.shared.tenant.TenantId;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Trace of every incident the service tried to deliver (prd.md FR-13). Events carry the type and the
 * outcome only: never the holder's name, email, phone, subject or description.
 */
public interface IncidentAuditTrail {

    enum Outcome { SENT, FAILED }

    /** @param reason why it failed, as an error code without personal data; empty when sent */
    record Event(UUID eventId, IncidentType type, Outcome outcome, Optional<String> reason, Instant occurredAt) {
    }

    void append(TenantId tenant, Event event);
}
