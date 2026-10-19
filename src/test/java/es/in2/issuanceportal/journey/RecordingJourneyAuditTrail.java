package es.in2.issuanceportal.journey;

import es.in2.issuanceportal.journey.application.port.JourneyAuditTrail;
import es.in2.issuanceportal.shared.tenant.TenantId;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Keeps the recorded events for assertions, or fails on demand to test that the trail never decides a request. */
public class RecordingJourneyAuditTrail implements JourneyAuditTrail {

    private final List<Event> events = new CopyOnWriteArrayList<>();
    private volatile boolean failing;

    @Override
    public void record(TenantId tenant, Event event) {
        if (failing) {
            throw new IllegalStateException("audit unavailable");
        }
        events.add(event);
    }

    public List<Event> events() {
        return List.copyOf(events);
    }

    public void failFromNowOn() {
        failing = true;
    }
}
