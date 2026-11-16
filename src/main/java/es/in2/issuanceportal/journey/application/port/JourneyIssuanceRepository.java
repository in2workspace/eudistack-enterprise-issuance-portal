package es.in2.issuanceportal.journey.application.port;

import es.in2.issuanceportal.journey.domain.JourneyIssuance;
import es.in2.issuanceportal.shared.tenant.TenantId;

import java.util.List;
import java.util.UUID;

/** The processes of the journeys of a tenant, one per request (design.md AD-16). Never deleted. */
public interface JourneyIssuanceRepository {

    /**
     * Records the process of a request. Call it with the journey's row locked: a request has one process, so a
     * second call for the same journey and request, or for the same process, is a programming error.
     */
    void insert(TenantId tenant, JourneyIssuance issuance);

    /** The journey's processes, oldest first. */
    List<JourneyIssuance> findByJourney(TenantId tenant, UUID journeyId);
}
