package es.in2.issuanceportal.authenticsource;

import es.in2.issuanceportal.authenticsource.application.port.AuthenticSourceAuditTrail;
import es.in2.issuanceportal.shared.tenant.TenantId;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** The trail in memory, so a test can check each event of a check. */
public class RecordingAuthenticSourceAuditTrail implements AuthenticSourceAuditTrail {

    private final List<Event> events = new CopyOnWriteArrayList<>();

    @Override
    public void append(TenantId tenant, Event event) {
        events.add(event);
    }

    public List<Event> events() {
        return List.copyOf(events);
    }
}
