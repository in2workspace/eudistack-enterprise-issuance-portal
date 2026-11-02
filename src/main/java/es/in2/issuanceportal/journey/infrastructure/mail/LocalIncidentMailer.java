package es.in2.issuanceportal.journey.infrastructure.mail;

import es.in2.issuanceportal.journey.application.port.IncidentMailer;
import es.in2.issuanceportal.journey.application.port.IncidentMessage;
import lombok.extern.slf4j.Slf4j;

/** For Docker Compose and tests: sends nothing and logs only that a message would have gone out. */
@Slf4j
class LocalIncidentMailer implements IncidentMailer {

    @Override
    public void send(IncidentMessage message) {
        log.info("Incident mail not sent: local mail provider");
    }
}
