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
    private final List<EmailCodeRequested> emailCodeRequests = new CopyOnWriteArrayList<>();
    private final List<EmailCodeChecked> emailCodeChecks = new CopyOnWriteArrayList<>();
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

    @Override
    public void append(TenantId tenant, EmailCodeRequested event) {
        if (failing) {
            throw new IllegalStateException("audit unavailable");
        }
        emailCodeRequests.add(event);
    }

    @Override
    public void append(TenantId tenant, EmailCodeChecked event) {
        if (failing) {
            throw new IllegalStateException("audit unavailable");
        }
        emailCodeChecks.add(event);
    }

    public List<EmailCodeChecked> emailCodeChecks() {
        return List.copyOf(emailCodeChecks);
    }

    public List<EmailCodeRequested> emailCodeRequests() {
        return List.copyOf(emailCodeRequests);
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
