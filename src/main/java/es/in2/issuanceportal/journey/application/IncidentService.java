package es.in2.issuanceportal.journey.application;

import es.in2.issuanceportal.journey.application.port.IncidentDeliveryException;
import es.in2.issuanceportal.journey.application.port.IncidentMailer;
import es.in2.issuanceportal.journey.application.port.IncidentMessage;
import es.in2.issuanceportal.journey.domain.config.Support;
import es.in2.issuanceportal.journey.domain.incident.Incident;
import es.in2.issuanceportal.shared.error.ProblemCode;
import es.in2.issuanceportal.shared.error.ProblemException;
import es.in2.issuanceportal.shared.tenant.TenantId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Sends a holder's incident to the tenant's support (prd.md §3.5, design.md AD-8). Nothing is stored:
 * the incident only lives for the time it takes to deliver it.
 */
@Slf4j
@Service
public class IncidentService {

    private final PortalConfigService configs;
    private final IncidentMailer mailer;

    public IncidentService(PortalConfigService configs, IncidentMailer mailer) {
        this.configs = configs;
        this.mailer = mailer;
    }

    /** @return the tenant's support response time, as shown to the holder */
    public String send(TenantId tenant, Incident incident) {
        Support support = configs.current(tenant).support();
        if (support.email() == null || support.responseTime() == null) {
            log.warn("Incident of tenant {} not sent: its portal configuration has no support email or response time",
                    tenant);
            throw new ProblemException(ProblemCode.SERVICE_UNAVAILABLE);
        }
        try {
            mailer.send(IncidentMessage.of(incident, support.email()));
        } catch (IncidentDeliveryException e) {
            log.warn("Incident of tenant {} not sent: {}", tenant, e.getMessage());
            throw new ProblemException(ProblemCode.SERVICE_UNAVAILABLE);
        }
        return support.responseTime();
    }
}
