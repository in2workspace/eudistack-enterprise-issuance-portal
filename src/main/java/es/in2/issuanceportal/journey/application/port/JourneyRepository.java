package es.in2.issuanceportal.journey.application.port;

import es.in2.issuanceportal.journey.domain.Journey;
import es.in2.issuanceportal.journey.domain.JourneyState;
import es.in2.issuanceportal.shared.tenant.TenantId;

import java.util.Optional;
import java.util.UUID;

/** Journeys of a tenant. There is no delete: a journey is never removed. */
public interface JourneyRepository {

    void insert(TenantId tenant, Journey journey);

    /**
     * Saves the journey only if it is still in the state it was read in. False means someone else
     * changed it first (a parallel request or the expiry job), and nothing was written.
     */
    boolean update(TenantId tenant, Journey journey, JourneyState readInState);

    Optional<Journey> findById(TenantId tenant, UUID id);
}
