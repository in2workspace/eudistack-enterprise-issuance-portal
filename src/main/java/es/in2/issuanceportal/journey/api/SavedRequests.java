package es.in2.issuanceportal.journey.api;

import es.in2.issuanceportal.shared.tenant.TenantId;

import java.util.UUID;

/**
 * Whether a request of the journey is already saved in the holder's wallet: declared saved by the holder, or saved
 * because its offer could no longer be renewed (design.md §3.8). Another module asks it before acting on a request
 * that this journey has already finished with.
 */
public interface SavedRequests {

    boolean isSaved(TenantId tenant, UUID journeyId, String requestId);
}
