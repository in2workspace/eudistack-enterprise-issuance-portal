package es.in2.issuanceportal.shared.audit;

import com.eudistack.auditoria.application.AuditPublisher;
import com.eudistack.auditoria.application.DisabledAuditPublisher;
import com.eudistack.auditoria.port.AuditAccessTokenProvider;
import com.eudistack.auditoria.port.AuditContextResolver;
import es.in2.issuanceportal.TestcontainersConfiguration;
import es.in2.issuanceportal.authenticsource.application.port.AuthenticSourceAuditTrail;
import es.in2.issuanceportal.journey.application.port.IncidentAuditTrail;
import es.in2.issuanceportal.journey.application.port.JourneyAuditTrail;
import es.in2.issuanceportal.shared.security.HolderRefs;
import es.in2.issuanceportal.shared.security.TestHolder;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The service as it is deployed by default, with both audit switches off. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuditSwitchedOffIT {

    private static final String CONTEXT = "/issuance-portal";

    @Autowired
    private ApplicationContext context;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private HolderRefs holderRefs;

    @Nested
    @DisplayName("AC-06 — audit switched off by default")
    class SwitchedOff {

        @Test
        void context_defaultSettings_registersNoAuditBeansOfThePortal() {
            // Arrange
            Class<AuditPublisher> publisherType = AuditPublisher.class;

            // Act
            AuditPublisher publisher = context.getBean(publisherType);

            // Assert
            assertThat(publisher).isInstanceOf(DisabledAuditPublisher.class);
            assertThat(context.getBeansOfType(AuditContextResolver.class)).isEmpty();
            assertThat(context.getBeansOfType(AuditAccessTokenProvider.class)).isEmpty();
        }

        @Test
        void context_defaultSettings_keepsTheLogTrails() {
            // Arrange
            List<Class<?>> ports = List.of(JourneyAuditTrail.class, AuthenticSourceAuditTrail.class,
                    IncidentAuditTrail.class);

            // Act
            List<Object> trails = ports.stream().<Object>map(context::getBean).toList();

            // Assert
            assertThat(trails).extracting(trail -> trail.getClass().getSimpleName()).containsExactly(
                    "LogJourneyAuditTrail", "LogAuthenticSourceAuditTrail", "LogIncidentAuditTrail");
        }

        @Test
        void openJourney_defaultSettings_succeedsWithoutAnOutbox() throws Exception {
            // Arrange
            TestHolder holder = TestHolder.of(new TenantId("sandbox"), holderRefs);

            // Act
            ResultActions response = mvc.perform(post(CONTEXT + "/api/v1/journey")
                    .contextPath(CONTEXT)
                    .with(request -> {
                        request.setServerName("sandbox.127.0.0.1.nip.io");
                        return request;
                    })
                    .with(holder.token()));

            // Assert
            response.andExpect(status().isCreated());
            assertThat(jdbc.sql("SELECT to_regclass('public.audit_outbox') IS NULL")
                    .query(Boolean.class).single()).isTrue();
        }
    }
}
