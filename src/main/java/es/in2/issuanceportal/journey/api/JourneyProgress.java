package es.in2.issuanceportal.journey.api;

import es.in2.issuanceportal.session.api.SessionId;
import es.in2.issuanceportal.shared.tenant.TenantId;

/** How the other modules record the step the holder reached through them. */
public interface JourneyProgress {

    enum Step {
        /** Written by Identification once the identity is in the session. */
        IDENTIFIED,
        /** Written by Authentic source once the entitlement is confirmed. */
        ENTITLEMENT_CONFIRMED
    }

    void reach(TenantId tenant, SessionId session, Step step);
}
