package es.in2.issuanceportal.session;

import es.in2.issuanceportal.session.api.JourneySessions;
import es.in2.issuanceportal.shared.tenant.TenantId;

import java.util.UUID;

class InMemoryJourneySessionsTest extends JourneySessionsContract {

    private final InMemoryJourneySessions sessions = new InMemoryJourneySessions();

    @Override
    JourneySessions sessions() {
        return sessions;
    }

    @Override
    UUID newJourney(TenantId tenant) {
        return UUID.randomUUID();
    }
}
