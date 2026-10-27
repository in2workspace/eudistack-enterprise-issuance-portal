package es.in2.issuanceportal.journey.application.port;

import es.in2.issuanceportal.journey.domain.Journey;
import es.in2.issuanceportal.journey.domain.JourneyState;
import es.in2.issuanceportal.shared.tenant.TenantId;

import java.time.Instant;
import java.util.List;
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

    // --- Expiry (design.md AD-4). These read and write the journey and its session in one statement,
    // so a request that renews the activity in between is never overwritten by the sweep.

    /**
     * Journeys with a session that may have to close: withdrawn (a session left behind) or with no
     * activity since {@code cutoff}. Ordered by id and paged after {@code afterId}, so a journey that
     * fails and is skipped does not come back in every batch.
     */
    List<UUID> findExpiryCandidates(TenantId tenant, Instant cutoff, UUID afterId, int limit);

    /**
     * Saves a journey withdrawn as expired only if it is still in progress and still has no activity
     * since {@code cutoff}. False when a request used it in the meantime.
     */
    boolean updateIfInactive(TenantId tenant, Journey journey, Instant cutoff);

    /**
     * Deletes the journey's session only if the journey is withdrawn or has no activity since
     * {@code cutoff}. False when there was nothing to delete or a request used the journey meanwhile.
     */
    boolean deleteSessionIfClosedOrInactive(TenantId tenant, UUID journeyId, Instant cutoff);
}
