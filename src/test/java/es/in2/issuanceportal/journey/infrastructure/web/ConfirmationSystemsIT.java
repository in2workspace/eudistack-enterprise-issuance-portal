package es.in2.issuanceportal.journey.infrastructure.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import es.in2.issuanceportal.session.api.IssuanceRecord;
import es.in2.issuanceportal.session.api.Section;
import es.in2.issuanceportal.session.api.SessionId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /journey/confirmation} of EUD-277 phase 1 through the service's real HTTP clients: the Verifier
 * that gives the system token, the Issuer and the tenant's Wallet Unit Service, over WireMock, with PostgreSQL.
 * The adapters have their own tests; this checks what the holder, the three systems and the audit trail see
 * together.
 */
class ConfirmationSystemsIT extends SystemIssuanceITSupport {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern CLIENT_ASSERTION = Pattern.compile("client_assertion=([^&]+)");
    private static final Pattern PROCESS_IN_TRAIL = Pattern.compile("issuance_process_id=([0-9a-f-]{36})");

    @Test
    @DisplayName("EUD-277 AC-03 — the Verifier, then the Issuer, then the Wallet Unit Service, and the journey completes")
    void confirm_entitledHolder_completesWithTheOfferAndTheOnboardingAddress() throws Exception {
        configure(true);
        everythingWorks();
        SessionId session = entitledJourney();

        MvcResult result = mvc.perform(confirmation(session, EMAIL))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.credential_offer_uri").value(OFFER))
                .andExpect(jsonPath("$.expires_at").isNotEmpty())
                .andExpect(jsonPath("$.onboarding_url").value(ONBOARDING_URL))
                .andExpect(jsonPath("$.onboarding_expires_at").isNotEmpty())
                .andReturn();

        UUID key = keyOf(session);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(key.toString());
        List<LoggedRequest> tokenRequests = requestsTo(TOKEN_PATH);
        assertThat(tokenRequests).hasSize(1);
        assertThat(tokenRequests.getFirst().getBodyAsString())
                .contains("grant_type=client_credentials", "client_assertion=");
        List<LoggedRequest> issuances = requestsTo(ISSUANCES_PATH);
        assertThat(issuances).hasSize(1);
        LoggedRequest issuance = issuances.getFirst();
        assertThat(issuance.getHeader("Authorization")).isEqualTo("Bearer " + ACCESS_TOKEN);
        assertThat(issuance.getHeader("X-Idempotency-Key")).isEqualTo(key.toString());
        JsonNode body = JSON.readTree(issuance.getBodyAsString());
        assertIssuanceBody(body);
        List<LoggedRequest> tickets = requestsTo(TICKETS_PATH);
        assertThat(tickets).hasSize(1);
        assertThat(tickets.getFirst().getHeader("Authorization")).isEqualTo("Bearer " + ACCESS_TOKEN);
        JsonNode ticket = JSON.readTree(tickets.getFirst().getBodyAsString());
        assertThat(ticket.get("email").asText()).isEqualTo(EMAIL);
        assertThat(ticket.get("credentialOffer").asText()).isEqualTo(OFFER);
        assertThat(ticket.get("expiresIn").asLong()).isBetween(530L, 540L);
        Map<String, Object> row = rowOf(session);
        assertThat(row).containsEntry("state", "completed")
                .containsEntry("step", "offer_obtained")
                .containsEntry("issuance_process_id", key);
        assertHolderTokenNeverSent();
    }

    @Test
    @DisplayName("EUD-277 AC-10 — a tenant without ticket never asks the Wallet Unit Service and answers only the offer")
    void confirm_tenantWithoutOnboarding_answersTheOfferWithoutCallingTheWalletUnitService() throws Exception {
        everythingWorks();
        SessionId session = entitledJourney();

        mvc.perform(confirmation(session, EMAIL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credential_offer_uri").value(OFFER))
                .andExpect(jsonPath("$.expires_at").isNotEmpty())
                .andExpect(jsonPath("$.onboarding_url").doesNotExist())
                .andExpect(jsonPath("$.onboarding_expires_at").doesNotExist());

        assertThat(requestsTo(TICKETS_PATH)).isEmpty();
        assertThat(rowOf(session)).containsEntry("state", "completed");
        assertHolderTokenNeverSent();
    }

    @Test
    @DisplayName("EUD-277 AC-01 — without a confirmed entitlement no system is asked and the journey does not change")
    void confirm_noEntitlement_isStepNotAllowedAndNoSystemIsAsked() throws Exception {
        configure(true);
        everythingWorks();
        SessionId session = identifiedJourney();
        Map<String, Object> before = rowOf(session);

        mvc.perform(confirmation(session, EMAIL))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("step_not_allowed"));

        assertThat(requestsToAnySystem()).isZero();
        assertThat(rowOf(session)).isEqualTo(before);
        assertThat(sessions.read(SANDBOX, session, Section.ISSUANCE)).isEmpty();
    }

    @Test
    @DisplayName("EUD-277 AC-05 — a tenant without machine credential gets no token and no issuance: 503 issuance_unavailable")
    void confirm_noMachineCredential_isIssuanceUnavailableAndNothingIsAsked() throws Exception {
        configure(true);
        everythingWorks();
        Files.deleteIfExists(SECRETS.resolve("sandbox.json"));
        SessionId session = entitledJourney();

        mvc.perform(confirmation(session, EMAIL))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("issuance_unavailable"));

        assertThat(requestsToAnySystem()).isZero();
        assertStillInProgressWithEntitlement(session);
    }

    @Test
    @DisplayName("EUD-277 ES-05 — a Verifier that rejects the client assertion gives 503 issuance_unavailable and no issuance")
    void confirm_verifierRejectsTheAssertion_isIssuanceUnavailableAndTheIssuerIsNotAsked() throws Exception {
        configure(true);
        issuerIssues();
        walletUnitServiceCreatesTicket();
        WIRE.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse().withStatus(401)
                .withHeader("Content-Type", "application/json").withBody("{\"error\":\"invalid_client\"}")));
        SessionId session = entitledJourney();

        mvc.perform(confirmation(session, EMAIL))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("issuance_unavailable"));

        assertThat(requestsTo(TOKEN_PATH)).hasSize(1);
        assertThat(requestsTo(ISSUANCES_PATH)).isEmpty();
        assertThat(requestsTo(TICKETS_PATH)).isEmpty();
        assertStillInProgressWithEntitlement(session);
    }

    @Test
    @DisplayName("EUD-277 EC-05 — the tenant's token is reused: two journeys, one request to the Verifier")
    void confirm_twoJourneysOfTheTenant_askTheVerifierOnce() throws Exception {
        everythingWorks();

        mvc.perform(confirmation(entitledJourney(), EMAIL)).andExpect(status().isOk());
        mvc.perform(confirmation(entitledJourney(), EMAIL)).andExpect(status().isOk());

        assertThat(requestsTo(TOKEN_PATH)).hasSize(1);
        assertThat(requestsTo(ISSUANCES_PATH)).hasSize(2);
    }

    @Test
    @DisplayName("EUD-277 AC-06 — after a network error the retry has the same key and the journey keeps one process")
    void confirm_networkErrorThenSuccess_retriesWithTheSameKeyAndKeepsOneProcess() throws Exception {
        verifierGrants();
        WIRE.stubFor(post(urlEqualTo(ISSUANCES_PATH)).inScenario("issuance").whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("recovered").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        WIRE.stubFor(post(urlEqualTo(ISSUANCES_PATH)).inScenario("issuance").whenScenarioStateIs("recovered")
                .willReturn(issued()));
        SessionId session = entitledJourney();

        mvc.perform(confirmation(session, EMAIL)).andExpect(status().isOk());

        List<LoggedRequest> attempts = requestsTo(ISSUANCES_PATH);
        assertThat(attempts).hasSize(2);
        assertThat(attempts).extracting(attempt -> attempt.getHeader("X-Idempotency-Key"))
                .containsOnly(keyOf(session).toString());
        assertThat(processOf(session)).isEqualTo(keyOf(session));
        assertThat(processesOfJourney(session)).isEqualTo(1);
    }

    @Test
    @DisplayName("EUD-277 AC-06 — a 4xx from the Issuer is not retried: 502 issuance_rejected")
    void confirm_issuerAnswers4xx_isIssuanceRejectedAndNotRetried() throws Exception {
        configure(true);
        verifierGrants();
        walletUnitServiceCreatesTicket();
        WIRE.stubFor(post(urlEqualTo(ISSUANCES_PATH)).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/problem+json").withBody("{\"type\":\"error\"}")));
        SessionId session = entitledJourney();

        mvc.perform(confirmation(session, EMAIL))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value("issuance_rejected"));

        assertThat(requestsTo(ISSUANCES_PATH)).hasSize(1);
        assertThat(requestsTo(TICKETS_PATH)).isEmpty();
        assertStillInProgressWithEntitlement(session);
    }

    @Test
    @DisplayName("EUD-277 ES-03 — a success without Location is 502 issuance_rejected and no ticket is asked")
    void confirm_issuerAnswersWithoutLocation_isIssuanceRejected() throws Exception {
        configure(true);
        verifierGrants();
        walletUnitServiceCreatesTicket();
        WIRE.stubFor(post(urlEqualTo(ISSUANCES_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"responses\":[{\"channel\":\"ui\",\"status\":200,\"body\":{\"credential_offer_uri\":\""
                        + OFFER + "\"}}]}")));
        SessionId session = entitledJourney();

        mvc.perform(confirmation(session, EMAIL))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value("issuance_rejected"));

        assertThat(requestsTo(TICKETS_PATH)).isEmpty();
        assertStillInProgressWithEntitlement(session);
        assertThat(logs.text()).doesNotContain(EMAIL, OFFER);
    }

    @Test
    @DisplayName("EUD-277 EC-02 — after issuance_unavailable the next confirmation reuses the key and ends with one process")
    void confirm_afterIssuanceUnavailable_reusesTheKeyAndEndsWithOneProcess() throws Exception {
        verifierGrants();
        WIRE.stubFor(post(urlEqualTo(ISSUANCES_PATH)).willReturn(aResponse().withStatus(500)));
        SessionId session = entitledJourney();
        mvc.perform(confirmation(session, EMAIL))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("issuance_unavailable"));
        UUID keptKey = keyOf(session);
        issuerIssues();

        mvc.perform(confirmation(session, EMAIL)).andExpect(status().isOk());

        assertThat(requestsTo(ISSUANCES_PATH)).extracting(request -> request.getHeader("X-Idempotency-Key"))
                .containsExactly(keptKey.toString(), keptKey.toString());
        assertThat(processOf(session)).isEqualTo(keptKey);
        assertThat(processesOfJourney(session)).isEqualTo(1);
    }

    @Test
    @DisplayName("EUD-277 NFR-2 — a confirmation repeated after completing, or after the ticket failed, adds no process")
    void confirm_repeated_neverAsksTheIssuerForAnotherProcess() throws Exception {
        everythingWorks();
        SessionId completed = entitledJourney();
        mvc.perform(confirmation(completed, EMAIL)).andExpect(status().isOk());
        mvc.perform(confirmation(completed, EMAIL)).andExpect(status().isGone())
                .andExpect(jsonPath("$.type").value("journey_closed"));
        assertThat(requestsTo(ISSUANCES_PATH)).hasSize(1);

        WIRE.resetRequests();
        configure(true);
        WIRE.stubFor(post(urlEqualTo(TICKETS_PATH)).willReturn(aResponse().withStatus(500)));
        SessionId withoutTicket = entitledJourney();
        mvc.perform(confirmation(withoutTicket, EMAIL)).andExpect(status().isServiceUnavailable());
        UUID process = processOf(withoutTicket);
        assertThat(process).isNotNull();
        walletUnitServiceCreatesTicket();

        mvc.perform(confirmation(withoutTicket, EMAIL))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("onboarding_unavailable"));

        assertThat(requestsTo(ISSUANCES_PATH)).hasSize(1);
        assertThat(requestsTo(TICKETS_PATH)).hasSize(1);
        assertThat(processOf(withoutTicket)).isEqualTo(process);
    }

    @Test
    @DisplayName("EUD-277 NFR-2 — two confirmations at once give one key to the Issuer and one process to the journey")
    void confirm_twoAtOnce_shareOneKeyAndEndWithOneProcess() throws Exception {
        verifierGrants();
        WIRE.stubFor(post(urlEqualTo(ISSUANCES_PATH)).willReturn(issued().withFixedDelay(300)));
        SessionId session = entitledJourney();
        CyclicBarrier together = new CyclicBarrier(2);

        List<MvcResult> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<MvcResult>> calls = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                calls.add(pool.submit(() -> {
                    together.await(10, TimeUnit.SECONDS);
                    return mvc.perform(confirmation(session, EMAIL)).andReturn();
                }));
            }
            for (Future<MvcResult> call : calls) {
                results.add(call.get(30, TimeUnit.SECONDS));
            }
        }

        assertThat(results).allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
        assertThat(results.get(0).getResponse().getContentAsString())
                .isEqualTo(results.get(1).getResponse().getContentAsString());
        Set<String> keys = requestsTo(ISSUANCES_PATH).stream()
                .map(request -> request.getHeader("X-Idempotency-Key")).collect(Collectors.toSet());
        assertThat(keys).containsExactly(keyOf(session).toString());
        assertThat(processOf(session)).isEqualTo(keyOf(session));
        assertThat(processesOfJourney(session)).isEqualTo(1);
    }

    @ParameterizedTest(name = "the Wallet Unit Service answers {0}")
    @ValueSource(ints = {400, 401, 500})
    @DisplayName("EUD-277 AC-12 — a Wallet Unit Service that rejects or fails: 503 onboarding_unavailable, process kept, no offer")
    void confirm_walletUnitServiceFails_isOnboardingUnavailableAndKeepsOnlyTheProcess(int status) throws Exception {
        configure(true);
        verifierGrants();
        issuerIssues();
        WIRE.stubFor(post(urlEqualTo(TICKETS_PATH)).willReturn(aResponse().withStatus(status)));
        SessionId session = entitledJourney();

        MvcResult result = mvc.perform(confirmation(session, EMAIL))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("onboarding_unavailable"))
                .andExpect(jsonPath("$.credential_offer_uri").doesNotExist())
                .andExpect(jsonPath("$.onboarding_url").doesNotExist())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("openid-credential-offer");
        Map<String, Object> row = rowOf(session);
        assertThat(row).containsEntry("state", "in_progress")
                .containsEntry("step", "entitlement_confirmed")
                .containsEntry("issuance_process_id", keyOf(session));
        IssuanceRecord stored = IssuanceRecord.fromJson(sessions.read(SANDBOX, session, Section.ISSUANCE).orElseThrow());
        assertThat(stored.offerUri()).isEmpty();
        assertThat(stored.onboarding()).isEmpty();
        assertThat(requestsTo(TICKETS_PATH)).hasSize(1);
        assertThat(logs.auditLines("ISSUANCE_REQUESTED")).singleElement().asString().contains("result=onboarding_failed");
    }

    /** How a confirmation ends, for the audit trail of AC-15. */
    enum Outcome {
        ISSUED("issued", true, true),
        ISSUER_REJECTS("rejected", false, false),
        ISSUER_UNAVAILABLE("unavailable", false, false),
        NO_SYSTEM_TOKEN("system_token_unavailable", false, false),
        TICKET_FAILS("onboarding_failed", true, true);

        final String result;
        final boolean hasProcess;
        final boolean asksTicket;

        Outcome(String result, boolean hasProcess, boolean asksTicket) {
            this.result = result;
            this.hasProcess = hasProcess;
            this.asksTicket = asksTicket;
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Outcome.class)
    @DisplayName("EUD-277 AC-15 — one event per issuance request and per ticket, and nothing personal or secret in any line")
    void confirm_anyOutcome_leavesOneEventPerRequestAndNoPersonalDataOrSecretInTheLogs(Outcome outcome) throws Exception {
        configure(true);
        everythingWorks();
        switch (outcome) {
            case ISSUER_REJECTS -> WIRE.stubFor(post(urlEqualTo(ISSUANCES_PATH)).willReturn(aResponse().withStatus(400)
                    .withBody("{\"detail\":\"" + EMAIL + " " + OFFER + "\"}")));
            case ISSUER_UNAVAILABLE -> WIRE.stubFor(post(urlEqualTo(ISSUANCES_PATH)).willReturn(aResponse().withStatus(500)
                    .withBody("{\"detail\":\"" + EMAIL + " " + OFFER + "\"}")));
            case NO_SYSTEM_TOKEN -> WIRE.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse().withStatus(401)
                    .withBody("{\"error\":\"invalid_client\"}")));
            case TICKET_FAILS -> WIRE.stubFor(post(urlEqualTo(TICKETS_PATH)).willReturn(aResponse().withStatus(500)
                    .withBody("{\"detail\":\"" + EMAIL + " " + OFFER + "\"}")));
            default -> {
                // everything works: already stubbed by everythingWorks()
            }
        }
        SessionId session = entitledJourney();

        mvc.perform(confirmation(session, EMAIL));

        List<String> issuance = logs.auditLines("ISSUANCE_REQUESTED");
        assertThat(issuance).singleElement().asString().contains("result=" + outcome.result);
        Matcher process = PROCESS_IN_TRAIL.matcher(issuance.getFirst());
        assertThat(process.find()).isEqualTo(outcome.hasProcess);
        if (outcome.hasProcess) {
            assertThat(UUID.fromString(process.group(1))).isEqualTo(processOf(session));
        }
        List<String> ticket = logs.auditLines("ONBOARDING_TICKET_REQUESTED");
        if (outcome.asksTicket) {
            assertThat(ticket).singleElement().asString()
                    .contains(outcome == Outcome.ISSUED ? "result=created" : "result=failed");
        } else {
            assertThat(ticket).isEmpty();
        }
        assertThat(logs.text()).doesNotContain(personalAndSecret());
        assertHolderTokenNeverSent();
    }

    // --- helpers ---

    private UUID keyOf(SessionId session) {
        return IssuanceRecord.fromJson(sessions.read(SANDBOX, session, Section.ISSUANCE).orElseThrow()).idempotencyKey();
    }

    private long processesOfJourney(SessionId session) {
        return jdbc.sql("SELECT count(issuance_process_id) FROM \"sandbox_issuance_portal\".journey WHERE id = :id")
                .param("id", journeyOf(session)).query(Long.class).single();
    }

    private static void assertIssuanceBody(JsonNode body) {
        assertThat(body.get("credential_configuration_id").asText()).isEqualTo("doctorid.sd.1");
        assertThat(body.get("email").asText()).isEqualTo(EMAIL);
        assertThat(body.get("delivery").asText()).isEqualTo("ui");
        assertThat(body.get("grant_type").asText()).isEqualTo("authorization_code");
        JsonNode payload = body.get("payload");
        assertThat(payload.get("givenName").asText()).isEqualTo(GIVEN_NAME);
        assertThat(payload.get("familyName").asText()).isEqualTo(FAMILY_NAME);
        assertThat(payload.get("personal_administrative_number").asText()).isEqualTo(ADMINISTRATIVE_NUMBER);
        assertThat(payload.get("medical_board").toString()).contains(MEDICAL_BOARD);
        assertThat(payload.get("medical_speciality").toString()).contains(SPECIALITY);
        assertThat(payload.get("legally_entitled").asBoolean()).isTrue();
        assertThat(payload.fieldNames()).toIterable().containsExactlyInAnyOrder("givenName", "familyName",
                "personal_administrative_number", "medical_board", "medical_speciality", "legally_entitled");
    }

    private void assertStillInProgressWithEntitlement(SessionId session) {
        Map<String, Object> row = rowOf(session);
        assertThat(row).containsEntry("state", "in_progress")
                .containsEntry("step", "entitlement_confirmed");
        assertThat(row.get("issuance_process_id")).isNull();
        assertThat(sessions.read(SANDBOX, session, Section.ENTITLEMENT)).isPresent();
    }

    /** What AC-15 keeps out of every line: the holder's data, the offer and address, and the tenant's secrets. */
    private String[] personalAndSecret() {
        List<String> values = new ArrayList<>(List.of(EMAIL, TOKEN_EMAIL, GIVEN_NAME, FAMILY_NAME, DNI,
                ADMINISTRATIVE_NUMBER, MEDICAL_BOARD, SPECIALITY, OFFER, "credential_offer_uri", ONBOARDING_URL,
                "ticket-fixed", ACCESS_TOKEN, HOLDER_TOKEN, MACHINE.credentialJwt(),
                MACHINE.key().getD().toString()));
        for (LoggedRequest request : WIRE.findAll(postRequestedFor(urlEqualTo(TOKEN_PATH)))) {
            Matcher found = CLIENT_ASSERTION.matcher(request.getBodyAsString());
            if (found.find()) {
                values.add(found.group(1));
            }
        }
        return values.toArray(String[]::new);
    }
}
