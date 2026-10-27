package es.in2.issuanceportal;

import es.in2.issuanceportal.journey.application.JourneyService;
import es.in2.issuanceportal.session.api.JourneySessions;
import es.in2.issuanceportal.session.application.port.PersonalDataCipher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IssuancePortalApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void applicationContext_testConfiguration_loads() {
        // Fails if any bean, port binding or configuration-properties record is misconfigured.
        assertThat(context.getBean(JourneyService.class)).isNotNull();
        assertThat(context.getBean(JourneySessions.class)).isNotNull();
        assertThat(context.getBean(PersonalDataCipher.class)).isNotNull();
    }
}
