package es.in2.issuanceportal.shared;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.issuanceportal.TestcontainersConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@Import(TestcontainersConfiguration.class)
class ObservabilityIT {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private MeterRegistry meters;
    @Autowired
    private ObjectMapper json;

    @Test
    void anErrorLogLineIsJsonWithTheFieldsOfTheLogContract() throws Exception {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        @SuppressWarnings("unchecked")
        OutputStreamAppender<ILoggingEvent> appender = (OutputStreamAppender<ILoggingEvent>)
                context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("JSON");
        MDC.put("tenant.id", "sandbox");
        MDC.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
        MDC.put("spanId", "00f067aa0ba902b7");
        LoggingEvent event;
        try {
            event = new LoggingEvent(Logger.class.getName(), context.getLogger("es.in2.issuanceportal.Test"),
                    Level.ERROR, "Something failed", new IllegalStateException("redacted reason"), null);
            event.getMDCPropertyMap();
        } finally {
            MDC.clear();
        }

        JsonNode line = json.readTree(appender.getEncoder().encode(event));

        assertThat(line.path("timestamp").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
        assertThat(line.path("level").asText()).isEqualTo("ERROR");
        assertThat(line.path("service.name").asText()).isEqualTo("eudistack-enterprise-issuance-portal");
        assertThat(line.path("service.version").asText()).isNotBlank().doesNotContain("@");
        assertThat(line.path("env").asText()).isEqualTo("local");
        assertThat(line.path("tenant.id").asText()).isEqualTo("sandbox");
        assertThat(line.path("trace_id").asText()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(line.path("span_id").asText()).isEqualTo("00f067aa0ba902b7");
        assertThat(line.path("error.type").asText()).isEqualTo("IllegalStateException");
        assertThat(line.path("error.message").asText()).isEqualTo("redacted reason");
    }

    @Test
    void requestMetricsCarryTheTenant() throws Exception {
        mvc.perform(post("/issuance-portal/api/v1/journey")
                .contextPath("/issuance-portal")
                .header("X-Requested-With", "issuance-portal")
                .with(request -> {
                    request.setServerName("sandbox.127.0.0.1.nip.io");
                    return request;
                }));

        assertThat(meters.find("http.server.requests").tag("tenant.id", "sandbox").timers()).isNotEmpty();
    }
}
