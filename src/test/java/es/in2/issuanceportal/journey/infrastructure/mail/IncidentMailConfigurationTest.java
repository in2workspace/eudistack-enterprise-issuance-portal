package es.in2.issuanceportal.journey.infrastructure.mail;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sesv2.SesV2Client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class IncidentMailConfigurationTest {

    private static final String FROM = "noreply@mail.stg.eudistack.net";

    @Test
    void mailerFor_sesProvider_returnsSesMailer() {
        IncidentMailProperties properties = new IncidentMailProperties(IncidentMailProperties.Provider.SES, FROM);

        assertThat(IncidentMailConfiguration.mailerFor(properties, () -> mock(SesV2Client.class)))
                .isInstanceOf(SesIncidentMailer.class);
    }

    @Test
    void mailerFor_localProvider_returnsLocalMailerWithoutCreatingSesClient() {
        IncidentMailProperties properties = new IncidentMailProperties(IncidentMailProperties.Provider.LOCAL, FROM);

        assertThat(IncidentMailConfiguration.mailerFor(properties, () -> {
            throw new AssertionError("No SES client in local");
        })).isInstanceOf(LocalIncidentMailer.class);
    }
}
