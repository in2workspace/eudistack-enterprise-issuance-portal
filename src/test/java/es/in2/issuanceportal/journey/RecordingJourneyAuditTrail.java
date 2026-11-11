package es.in2.issuanceportal.journey;

import es.in2.issuanceportal.journey.application.port.JourneyAuditTrail;
import es.in2.issuanceportal.shared.tenant.TenantId;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Keeps the recorded events for assertions, or fails on demand to test that the trail never decides a request. */
public class RecordingJourneyAuditTrail implements JourneyAuditTrail {

    private final List<Event> events = new CopyOnWriteArrayList<>();
    private final List<IssuanceRequested> issuanceRequests = new CopyOnWriteArrayList<>();
    private final List<OnboardingTicketRequested> ticketRequests = new CopyOnWriteArrayList<>();
    private volatile boolean failing;

    @Override
    public void append(TenantId tenant, Event event) {
        if (failing) {
            throw new IllegalStateException("audit unavailable");
        }
        events.add(event);
    }

    @Override
    public void append(TenantId tenant, IssuanceRequested event) {
        if (failing) {
            throw new IllegalStateException("audit unavailable");
        }
        issuanceRequests.add(event);
    }

    @Override
    public void append(TenantId tenant, OnboardingTicketRequested event) {
        if (failing) {
            throw new IllegalStateException("audit unavailable");
        }
        ticketRequests.add(event);
    }

    public List<OnboardingTicketRequested> ticketRequests() {
        return List.copyOf(ticketRequests);
    }

    public List<IssuanceRequested> issuanceRequests() {
        return List.copyOf(issuanceRequests);
    }

    public List<Event> events() {
        return List.copyOf(events);
    }

    public void failFromNowOn() {
        failing = true;
    }
}
