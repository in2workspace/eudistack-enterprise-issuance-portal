package es.in2.issuanceportal.journey.api;

import es.in2.issuanceportal.session.api.SessionId;
import es.in2.issuanceportal.shared.tenant.TenantId;

/**
 * How another module withdraws the journey. The journey keeps the step reached and the reason, and
 * its session with all the personal data is deleted.
 */
public interface JourneyWithdrawal {

    enum Cause {
        /** The authentic source did not confirm the holder's entitlement. */
        NOT_ENTITLED
    }

    void withdraw(TenantId tenant, SessionId session, Cause cause);
}
