package es.in2.issuanceportal.journey.infrastructure.web;

import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.issuanceportal.TestcontainersConfiguration;
import es.in2.issuanceportal.journey.RecordingEmailCodeMailer;
import es.in2.issuanceportal.journey.application.JourneyService;
import es.in2.issuanceportal.shared.security.TestHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every operation of the service with the audit publisher on and the dispatcher off, over HTTP and PostgreSQL: what
 * each one leaves in the outbox, and what a failing outbox undoes.
 */
@SpringBootTest(properties = "auditoria.publisher.enabled=true")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, AuditOutboxFlowIT.Mail.class})
@DirtiesContext
class AuditOutboxFlowIT extends SystemIssuanceITSupport {

    private static final String API = CONTEXT + "/api/v1";
    private static final String SUPPORT_EMAIL = "support@example.test";
    private static final String IDENTITY_ONLY_CONFIG = """
            {"texts": {}, "legal_links": {},
             "support": {"email": "support@example.test", "response_time": "48 hours"},
             "wallet_url": "https://sandbox.127.0.0.1.nip.io:4443/wallet",
             "attestation_type": "doctorid.sd.1",
             "data_placement": {"givenName": "identity.given_name", "familyName": "identity.family_name"},
             "onboarding": {"enabled": false}}""";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TestConfiguration
    static class Mail {
        @Bean
        @Primary
        RecordingEmailCodeMailer recordingEmailCodeMailer() {
            return new RecordingEmailCodeMailer();
        }
    }

    @Autowired
    private RecordingEmailCodeMailer mailer;
    @Autowired
    private JourneyService journeys;

    /** A row of the outbox. */
    private record Staged(String type, String tenant, String status, String correlationId, JsonNode payload) {

        String result() {
            return payload.get("result").asText();
        }

        String reason() {
            return payload.path("reason").asText(null);
        }

        String actorKind() {
            return payload.get("actor").get("kind").asText();
        }

        String actorId() {
            return payload.get("actor").get("id").asText();
        }
    }

    @BeforeEach
    void setUpOutbox() {
        everythingWorks();
        configure(IDENTITY_ONLY_CONFIG);
        jdbc.sql("DELETE FROM public.audit_outbox").update();
    }

    @AfterEach
    void restoreOutbox() {
        jdbc.sql("DROP TRIGGER IF EXISTS reject_event ON public.audit_outbox").update();
    }

    @Nested
    @DisplayName("AC-01, AC-03, AC-04, AC-05 — a whole journey")
    class WholeJourney {

        @Test
        void journey_fromOpeningToEnd_leavesOnePendingEventPerFact() throws Exception {
            // Arrange
            TestHolder holder = TestHolder.of(SANDBOX, holderRefs);

            // Act
            UUID journey = completeAndEnd(holder);

            // Assert
            List<Staged> events = outbox();
            assertThat(events).extracting(Staged::type, Staged::result, Staged::reason).containsExactly(
                    tuple("JourneyStarted", "Success", null),
                    tuple("EntitlementChecked", "Success", null),
                    tuple("JourneyStepReached", "Success", null),
                    tuple("EmailVerificationCodeRequested", "Success", null),
                    tuple("EmailVerificationCodeChecked", "Denied", "InvalidCode"),
                    tuple("JourneyStepReached", "Success", null),
                    tuple("EmailVerificationCodeChecked", "Success", null),
                    tuple("PortalIssuanceRequested", "Success", null),
                    tuple("JourneyCompleted", "Success", null),
                    tuple("IssuanceRequestSaved", "Success", null),
                    tuple("JourneyEnded", "Success", null));
            assertThat(events).allSatisfy(event -> {
                assertThat(event.tenant()).isEqualTo("sandbox");
                assertThat(event.status()).isEqualTo("PENDING");
                assertThat(event.correlationId()).isEqualTo(journey.toString());
                assertThat(event.actorKind()).isEqualTo("Holder");
                assertThat(event.actorId()).isEqualTo(holder.ref().value());
                assertThat(event.payload().get("resource").asText()).isEqualTo("journey:" + journey);
                assertThat(event.payload().get("event_version").asText()).isEqualTo("2.5.0");
                assertThat(event.payload().get("source").asText()).isEqualTo("issuance-portal");
            });
        }

        @Test
        void journey_fromOpeningToEnd_putsNoPersonalDataNorSecretInItsEvents() throws Exception {
            // Arrange
            TestHolder holder = TestHolder.of(SANDBOX, holderRefs);

            // Act
            completeAndEnd(holder);

            // Assert
            String payloads = outbox().stream().map(event -> event.payload().toString()).reduce("", String::concat);
            assertThat(payloads).isNotEmpty()
                    .doesNotContain(EMAIL, TOKEN_EMAIL, holder.subject(), RecordingEmailCodeMailer.codeOf(mailer.last()),
                            "openid-credential-offer", ACCESS_TOKEN, "given_name", "family_name");
        }

        @Test
        void journey_fromOpeningToEnd_writesNoAuditLogNorError() throws Exception {
            // Arrange
            TestHolder holder = TestHolder.of(SANDBOX, holderRefs);

            // Act
            completeAndEnd(holder);

            // Assert
            assertThat(logs.events()).isNotEmpty().allSatisfy(line -> {
                assertThat(line.getLoggerName()).doesNotStartWith("audit.");
                assertThat(line.getLevel()).isNotEqualTo(Level.ERROR);
            });
        }
    }

    @Nested
    @DisplayName("EC-01 to EC-05 — other paths")
    class OtherPaths {

        @Test
        void openJourney_twiceBySameHolder_leavesOneStartedEvent() throws Exception {
            // Arrange
            TestHolder holder = TestHolder.of(SANDBOX, holderRefs);
            open(holder).andExpect(status().isCreated());

            // Act
            open(holder).andExpect(status().isOk());

            // Assert
            assertThat(outbox()).extracting(Staged::type).containsExactly("JourneyStarted");
        }

        @Test
        void sendCode_tooSoon_keepsTheRefusalEvent() throws Exception {
            // Arrange
            TestHolder holder = entitled(TestHolder.of(SANDBOX, holderRefs));
            sendCode(holder).andExpect(status().is2xxSuccessful());

            // Act
            ResultActions response = sendCode(holder);

            // Assert
            response.andExpect(status().isTooManyRequests());
            assertThat(outbox()).last().satisfies(event -> {
                assertThat(event.type()).isEqualTo("EmailVerificationCodeRequested");
                assertThat(event.result()).isEqualTo("Denied");
                assertThat(event.reason()).isEqualTo("ResendTooSoon");
            });
        }

        @Test
        void withdrawal_journeyInProgress_leavesACancellationByTheHolder() throws Exception {
            // Arrange
            TestHolder holder = TestHolder.of(SANDBOX, holderRefs);
            open(holder).andExpect(status().isCreated());

            // Act
            mvc.perform(call(holder, "/journey/withdrawal")).andExpect(status().is2xxSuccessful());

            // Assert
            assertThat(outbox()).last().satisfies(event -> {
                assertThat(event.type()).isEqualTo("JourneyWithdrawn");
                assertThat(event.actorKind()).isEqualTo("Holder");
                assertThat(event.payload().get("details").get("withdrawal_reason").asText()).isEqualTo("cancelled");
            });
        }

        @Test
        void expireInactive_withoutARequest_leavesAnExpiryByThePortal() throws Exception {
            // Arrange
            TestHolder holder = TestHolder.of(SANDBOX, holderRefs);
            open(holder).andExpect(status().isCreated());
            UUID journey = journeyOf(holder);
            jdbc.sql("UPDATE \"sandbox_issuance_portal\".journey SET last_activity = now() - interval '1 hour' "
                    + "WHERE id = :id").param("id", journey).update();

            // Act
            journeys.expireInactive(SANDBOX);

            // Assert
            assertThat(outbox()).filteredOn(event -> event.correlationId().equals(journey.toString())).last()
                    .satisfies(event -> {
                        assertThat(event.type()).isEqualTo("JourneyWithdrawn");
                        assertThat(event.tenant()).isEqualTo("sandbox");
                        assertThat(event.actorKind()).isEqualTo("System");
                        assertThat(event.actorId()).isEqualTo("issuance-portal");
                    });
        }

        @Test
        void dispute_checkedRequest_leavesTheNotice() throws Exception {
            // Arrange
            TestHolder holder = entitled(TestHolder.of(SANDBOX, holderRefs));

            // Act
            mvc.perform(call(holder, "/entitlement/requests/r1/dispute")).andExpect(status().isNoContent());

            // Assert
            assertThat(outbox()).last().satisfies(event -> {
                assertThat(event.type()).isEqualTo("RequestDataDisputed");
                assertThat(event.payload().get("details").get("request_id").asText()).isEqualTo("r1");
                assertThat(event.payload().get("details").get("notice").asText()).isEqualTo("not_supported");
            });
        }

        @Test
        void incident_sentWithoutToken_leavesAnEventOfThePortalWithoutTheForm() throws Exception {
            // Arrange
            String incident = """
                    {"name": "Elena Ruiz", "email": "elena.ruiz@example.test", "phone": "600000000",
                     "type": "access", "subject": "Cannot sign in", "description": "My secret description"}""";

            // Act
            ResultActions response = mvc.perform(MockMvcRequestBuilders.post(API + "/incidents")
                    .contextPath(CONTEXT)
                    .with(host())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(incident));

            // Assert
            response.andExpect(status().isAccepted());
            assertThat(outbox()).singleElement().satisfies(event -> {
                assertThat(event.type()).isEqualTo("SupportIncidentDelivered");
                assertThat(event.actorKind()).isEqualTo("System");
                assertThat(event.payload().get("resource").asText()).startsWith("incident:");
                assertThat(event.payload().toString()).doesNotContain("Elena", "600000000", "Cannot sign in",
                        "secret description", EMAIL, SUPPORT_EMAIL);
            });
        }
    }

    @Nested
    @DisplayName("AC-02, ES-01 — a failing outbox")
    class FailingOutbox {

        @Test
        void openJourney_outboxRejectsTheEvent_leavesNoJourney() throws Exception {
            // Arrange
            TestHolder holder = TestHolder.of(SANDBOX, holderRefs);
            reject("JourneyStarted");

            // Act
            ResultActions response = open(holder);

            // Assert
            response.andExpect(status().is5xxServerError());
            assertThat(sessions.journeyOf(SANDBOX, holder.ref())).isEmpty();
            assertThat(outbox()).isEmpty();
        }

        @Test
        void confirmation_outboxRejectsTheCompletion_undoesTheJourneyChange() throws Exception {
            // Arrange
            TestHolder holder = verified(TestHolder.of(SANDBOX, holderRefs));
            reject("JourneyCompleted");

            // Act
            ResultActions response = mvc.perform(confirmation(holder));

            // Assert
            response.andExpect(status().is5xxServerError());
            assertThat(rowOf(holder)).containsEntry("state", "in_progress").containsEntry("step", "email_verified");
            assertThat(processOf(holder)).isNull();
            assertThat(outbox()).extracting(Staged::type)
                    .contains("PortalIssuanceRequested")
                    .doesNotContain("JourneyCompleted");
        }

        @Test
        void confirmation_outboxRejectsTheIssuanceRequest_stillGivesTheOffer() throws Exception {
            // Arrange
            TestHolder holder = verified(TestHolder.of(SANDBOX, holderRefs));
            reject("PortalIssuanceRequested");

            // Act
            ResultActions response = mvc.perform(confirmation(holder));

            // Assert
            response.andExpect(status().isOk());
            assertThat(rowOf(holder)).containsEntry("state", "completed");
            assertThat(outbox()).extracting(Staged::type)
                    .contains("JourneyCompleted")
                    .doesNotContain("PortalIssuanceRequested");
        }
    }

    /** Opens, confirms the entitlement, verifies the email after a wrong code, confirms, saves and leaves. */
    private UUID completeAndEnd(TestHolder holder) throws Exception {
        entitled(holder);
        UUID journey = journeyOf(holder);
        sendCode(holder).andExpect(status().is2xxSuccessful());
        String code = RecordingEmailCodeMailer.codeOf(mailer.last());
        checkCode(holder, code.equals("000000") ? "111111" : "000000").andExpect(status().isBadRequest());
        checkCode(holder, code).andExpect(status().is2xxSuccessful());
        mvc.perform(confirmation(holder)).andExpect(status().isOk());
        mvc.perform(call(holder, "/journey/requests/r1/saved")).andExpect(status().isNoContent());
        mvc.perform(call(holder, "/journey/withdrawal")).andExpect(status().is2xxSuccessful());
        return journey;
    }

    private TestHolder entitled(TestHolder holder) throws Exception {
        open(holder).andExpect(status().isCreated());
        mvc.perform(call(holder, "/entitlement")).andExpect(status().isOk());
        return holder;
    }

    private TestHolder verified(TestHolder holder) throws Exception {
        entitled(holder);
        sendCode(holder).andExpect(status().is2xxSuccessful());
        checkCode(holder, RecordingEmailCodeMailer.codeOf(mailer.last())).andExpect(status().is2xxSuccessful());
        return holder;
    }

    private ResultActions open(TestHolder holder) throws Exception {
        return mvc.perform(call(holder, "/journey"));
    }

    private ResultActions sendCode(TestHolder holder) throws Exception {
        return mvc.perform(call(holder, "/journey/email-verification")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + EMAIL + "\"}"));
    }

    private ResultActions checkCode(TestHolder holder, String code) throws Exception {
        return mvc.perform(call(holder, "/journey/email-verification/code")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}"));
    }

    private static MockHttpServletRequestBuilder call(TestHolder holder, String path) {
        return MockMvcRequestBuilders.post(API + path)
                .contextPath(CONTEXT)
                .with(host())
                .with(holder.token());
    }

    private void reject(String eventType) {
        jdbc.sql("CREATE OR REPLACE FUNCTION public.reject_audit_event() RETURNS trigger LANGUAGE plpgsql AS "
                + "'BEGIN IF NEW.event_type = TG_ARGV[0] THEN RAISE EXCEPTION ''outbox unavailable''; END IF; "
                + "RETURN NEW; END'").update();
        jdbc.sql("CREATE TRIGGER reject_event BEFORE INSERT ON public.audit_outbox FOR EACH ROW "
                + "EXECUTE FUNCTION public.reject_audit_event('" + eventType + "')").update();
    }

    private List<Staged> outbox() {
        return jdbc.sql("SELECT event_type, tenant, status, correlation_id, payload::text AS payload "
                        + "FROM public.audit_outbox ORDER BY created_at")
                .query((rs, row) -> new Staged(rs.getString("event_type"), rs.getString("tenant"),
                        rs.getString("status"), rs.getString("correlation_id"), json(rs.getString("payload"))))
                .list();
    }

    private static JsonNode json(String payload) {
        try {
            return JSON.readTree(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
