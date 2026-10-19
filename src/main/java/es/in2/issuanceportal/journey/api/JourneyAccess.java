package es.in2.issuanceportal.journey.api;

import es.in2.issuanceportal.session.api.SessionId;
import es.in2.issuanceportal.shared.tenant.TenantId;

/**
 * The check every module makes at the start of a request: the session belongs to a journey that is
 * still open. It records the activity and applies the inactivity expiry on the spot, so a stale
 * session answers {@code journey_closed} even before the expiry job runs.
 */
public interface JourneyAccess {

    void requireActive(TenantId tenant, SessionId session);
}
