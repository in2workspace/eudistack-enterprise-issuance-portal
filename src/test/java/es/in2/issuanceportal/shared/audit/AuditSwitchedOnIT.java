package es.in2.issuanceportal.shared.audit;

import com.eudistack.auditoria.application.AuditPublisher;
import com.eudistack.auditoria.domain.AuditDraft;
import com.eudistack.auditoria.domain.AuditFact;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import es.in2.issuanceportal.TestcontainersConfiguration;
import es.in2.issuanceportal.journey.infrastructure.systemtoken.MachineCredentialFixture;
import es.in2.issuanceportal.shared.security.HolderRefs;
import es.in2.issuanceportal.shared.security.TestHolder;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The starter wired into the service with both switches on, against PostgreSQL, a simulated Verifier and an Audit
 * API that is unavailable, so the staged row stays in the outbox. The service emits no event yet: a probe stages one
 * in the request of a holder.
 */
@SpringBootTest(properties = {
        "auditoria.publisher.enabled=true",
        "auditoria.dispatcher.enabled=true",
        "auditoria.dispatcher.poll-interval=200ms",
        "issuance-portal.audit-token.tenant=platform",
        "issuance-portal.audit-token.assertion-audience=http://verifier-service:8080/verifier"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, AuditSwitchedOnIT.AuditProbe.class})
@DirtiesContext
class AuditSwitchedOnIT {

    private static final String CONTEXT = "/issuance-portal";
    private static final String TOKEN_PATH = "/verifier/oidc/token";
    private static final String EVENTS_PATH = "/api/v1/events";
    private static final MachineCredentialFixture CREDENTIAL = MachineCredentialFixture.create("platform");
    private static final WireMockServer PLATFORM = startPlatform();

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private HolderRefs holderRefs;

    @DynamicPropertySource
    static void auditSettings(DynamicPropertyRegistry registry) {
        registry.add("auditoria.client.base-url", PLATFORM::baseUrl);
        registry.add("issuance-portal.audit-token.token-url", () -> PLATFORM.baseUrl() + TOKEN_PATH);
        registry.add("issuance-portal.audit-token.client-id", CREDENTIAL::clientId);
        registry.add("issuance-portal.audit-token.credential", CREDENTIAL::credentialJwt);
        registry.add("issuance-portal.audit-token.private-key", () -> CREDENTIAL.key().toJSONString());
    }

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM public.audit_outbox").update();
    }

    @AfterAll
    static void stopPlatform() {
        PLATFORM.stop();
    }

    @Nested
    @DisplayName("AC-05, AC-08 — audit switched on")
    class SwitchedOn {

        @Test
        void stage_requestOfAHolder_storesTheEventWithItsTenantAndHolderReference() throws Exception {
            // Arrange
            TestHolder holder = TestHolder.of(new TenantId("sandbox"), holderRefs);

            // Act
            ResultActions response = stageAs(holder);

            // Assert
            response.andExpect(status().isNoContent());
            JsonNode event = new ObjectMapper().readTree(
                    jdbc.sql("SELECT payload::text FROM public.audit_outbox").query(String.class).single());
            assertThat(event.get("tenant").asText()).isEqualTo("sandbox");
            assertThat(event.get("source").asText()).isEqualTo("issuance-portal");
            assertThat(event.get("event_version").asText()).isEqualTo("2.5.0");
            assertThat(event.get("actor").get("kind").asText()).isEqualTo("Holder");
            assertThat(event.get("actor").get("id").asText()).isEqualTo(holder.ref().value());
            assertThat(event.get("actor").fieldNames()).toIterable().containsExactlyInAnyOrder("kind", "id");
        }

        @Test
        void dispatch_stagedEvent_postsItWithTheTokenOfTheAuditAttestation() throws Exception {
            // Arrange
            TestHolder holder = TestHolder.of(new TenantId("sandbox"), holderRefs);

            // Act
            stageAs(holder).andExpect(status().isNoContent());

            // Assert
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> PLATFORM.verify(
                    postRequestedFor(urlEqualTo(EVENTS_PATH))
                            .withHeader("Authorization", equalTo("Bearer audit-token"))
                            .withRequestBody(containing(holder.ref().value()))));
            PLATFORM.verify(postRequestedFor(urlEqualTo(TOKEN_PATH)).withHeader("X-Tenant", equalTo("platform")));
        }
    }

    private ResultActions stageAs(TestHolder holder) throws Exception {
        return mvc.perform(post(CONTEXT + "/api/v1/probe/audit")
                .contextPath(CONTEXT)
                .with(request -> {
                    request.setServerName("sandbox.127.0.0.1.nip.io");
                    return request;
                })
                .with(holder.token()));
    }

    private static WireMockServer startPlatform() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        server.stubFor(WireMock.post(urlEqualTo(TOKEN_PATH))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"audit-token\",\"expires_in\":900}")));
        server.stubFor(WireMock.post(urlEqualTo(EVENTS_PATH))
                .willReturn(aResponse().withStatus(503)));
        return server;
    }

    @RestController
    static class AuditProbe {

        private final AuditPublisher publisher;
        private final TransactionOperations transactions;

        AuditProbe(AuditPublisher publisher, TransactionOperations transactions) {
            this.publisher = publisher;
            this.transactions = transactions;
        }

        @PostMapping("/api/v1/probe/audit")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void stage() {
            AuditFact fact = AuditFact.builder("ExternalSystemAccess")
                    .action("AccessExternalSystem")
                    .resource("system:mail-provider")
                    .details(Map.of("system_type", "MailProvider", "system", "mail-provider",
                            "operation", "send", "authentication_method", "Basic", "duration_ms", 1))
                    .build();
            transactions.executeWithoutResult(status -> publisher.stage(AuditDraft.success(fact)));
        }
    }
}
