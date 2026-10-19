package es.in2.issuanceportal;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IssuancePortalApplicationTests {

    @Test
    void contextLoads() {
        // Fails if any bean, port binding or configuration-properties record is misconfigured.
    }
}
