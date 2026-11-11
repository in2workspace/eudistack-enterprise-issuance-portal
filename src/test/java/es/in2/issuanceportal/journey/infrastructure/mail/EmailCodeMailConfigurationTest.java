package es.in2.issuanceportal.journey.infrastructure.mail;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import es.in2.issuanceportal.journey.application.port.EmailCodeMessage;
import es.in2.issuanceportal.journey.domain.EmailLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sesv2.SesV2Client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class EmailCodeMailConfigurationTest {

    private static final String FROM = "noreply@mail.stg.eudistack.net";

    @Test
    void mailerFor_sesProvider_returnsSesMailer() {
        IncidentMailProperties properties = new IncidentMailProperties(IncidentMailProperties.Provider.SES, FROM);

        assertThat(EmailCodeMailConfiguration.mailerFor(properties, () -> mock(SesV2Client.class)))
                .isInstanceOf(SesEmailCodeMailer.class);
    }

    @Test
    void mailerFor_localProvider_returnsLocalMailerWithoutCreatingSesClient() {
        IncidentMailProperties properties = new IncidentMailProperties(IncidentMailProperties.Provider.LOCAL, FROM);

        assertThat(EmailCodeMailConfiguration.mailerFor(properties, () -> {
            throw new AssertionError("No SES client in local");
        })).isInstanceOf(LocalEmailCodeMailer.class);
    }

    @Test
    @DisplayName("AC-10 — send_localMailer_logsThatItSentNothingAndNeverTheCodeOrTheAddress")
    void send_localMailer_logsThatItSentNothingAndNeverTheCodeOrTheAddress() {
        Logger logger = (Logger) LoggerFactory.getLogger(LocalEmailCodeMailer.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            new LocalEmailCodeMailer().send(EmailCodeMessage.of("ana@example.org", "042042", "DoctorID",
                    EmailLanguage.ES));
        } finally {
            logger.detachAppender(logs);
        }

        assertThat(logs.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).isEqualTo("email code not sent: local mail provider");
            assertThat(event.getArgumentArray()).isNull();
        });
    }
}
