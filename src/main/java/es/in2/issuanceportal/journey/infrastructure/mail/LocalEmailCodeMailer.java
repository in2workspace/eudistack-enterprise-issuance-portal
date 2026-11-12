package es.in2.issuanceportal.journey.infrastructure.mail;

import es.in2.issuanceportal.journey.application.port.EmailCodeMailer;
import es.in2.issuanceportal.journey.application.port.EmailCodeMessage;
import lombok.extern.slf4j.Slf4j;

/**
 * For tests: sends nothing and logs only that a message would have gone out. It never writes the code or the
 * address, so a log gives no way to verify an email.
 */
@Slf4j
class LocalEmailCodeMailer implements EmailCodeMailer {

    @Override
    public void send(EmailCodeMessage message) {
        log.info("email code not sent: local mail provider");
    }
}
