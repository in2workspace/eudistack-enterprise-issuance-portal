package es.in2.issuanceportal.journey.infrastructure.walletunit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import es.in2.issuanceportal.MutableClock;
import es.in2.issuanceportal.journey.InMemorySystemTokenProvider;
import es.in2.issuanceportal.journey.application.port.OnboardingTicket;
import es.in2.issuanceportal.journey.application.port.OnboardingTicketException;
import es.in2.issuanceportal.journey.application.port.OnboardingTicketException.Reason;
import es.in2.issuanceportal.journey.application.port.OnboardingTickets;
import es.in2.issuanceportal.journey.application.port.SystemTokenUnavailableException;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The onboarding ticket client against a simulated Wallet Unit Service, with the wire contract of
 * {@code createOnboardingTicket} (EUD-8 annex {@code openapi.yaml}): what the request carries, that it is never
 * retried, how each answer is read, and that nothing personal or secret reaches the logs.
 */
class OnboardingTicketsIT {

    private static final String PATH = "/business-wallet/api/v1/onboarding-tickets";
    private static final TenantId SANDBOX = new TenantId("sandbox");
    private static final String EMAIL = "elena.ruiz@example.test";
    private static final String OFFER = "openid-credential-offer://?credential_offer_uri=https%3A%2F%2Fissuer.test%2Foffer%2Fsecret-9c1e";
    private static final String ONBOARDING_URL = "https://sandbox.wallet.test/t/Zk3q9Xw2Lp7Rt4Vb8Nc1Ha";
    private static final Duration EXPIRES_IN = Duration.ofSeconds(540);
    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    private WireMockServer walletUnitService;
    private MutableClock clock;
    private InMemorySystemTokenProvider tokens;
    private ListAppender<ILoggingEvent> logs;
    private Logger rootLogger;

    @BeforeEach
    void setUp() {
        walletUnitService = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        walletUnitService.start();
        clock = new MutableClock(NOW);
        tokens = new InMemorySystemTokenProvider(clock);
        logs = new ListAppender<>();
        logs.start();
        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(logs);
        walletUnitService.stop();
    }

    private String url() {
        return "http://localhost:" + walletUnitService.port() + "/business-wallet/api/v1";
    }

    private OnboardingTickets client(Duration timeout) {
        return new WalletUnitServiceConfiguration().onboardingTickets(new WalletUnitServiceProperties(timeout),
                tokens, clock);
    }

    private OnboardingTickets client() {
        return client(Duration.ofSeconds(5));
    }

    private OnboardingTicket request(OnboardingTickets client) {
        return client.request(SANDBOX, url(), EMAIL, OFFER, EXPIRES_IN);
    }

    private void answers(ResponseDefinitionBuilder response) {
        walletUnitService.stubFor(post(urlEqualTo(PATH)).willReturn(response));
    }

    private static ResponseDefinitionBuilder created(String body) {
        return aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody(body);
    }

    private static ResponseDefinitionBuilder created() {
        return created("{\"onboardingUrl\":\"" + ONBOARDING_URL + "\",\"expiresIn\":300}");
    }

    private static ResponseDefinitionBuilder status(int status) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/problem+json")
                .withBody("{\"type\":\"error\",\"detail\":\"" + EMAIL + " " + OFFER + "\"}");
    }

    private List<LoggedRequest> requests() {
        return walletUnitService.findAll(postRequestedFor(anyUrl()));
    }

    private static OnboardingTicketException failureOf(Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(OnboardingTicketException.class);
        return (OnboardingTicketException) thrown;
    }

    @Test
    @DisplayName("AC-03 — the request carries the email, the offer as is and the lifetime, with the system token")
    void request_postsTheTicketAsSystem() throws Exception {
        answers(created());

        OnboardingTicket ticket = request(client());

        assertThat(ticket.onboardingUrl()).isEqualTo(ONBOARDING_URL);
        assertThat(requests()).hasSize(1);
        LoggedRequest sent = requests().get(0);
        assertThat(sent.getHeader("Authorization")).isEqualTo("Bearer token-1");
        assertThat(sent.getHeader("Content-Type")).startsWith("application/json");
        JsonNode body = JSON.readTree(sent.getBodyAsString());
        assertThat(body.get("email").asText()).isEqualTo(EMAIL);
        assertThat(body.get("credentialOffer").asText()).isEqualTo(OFFER);
        assertThat(body.get("expiresIn").asLong()).isEqualTo(540);
        assertThat(body.size()).isEqualTo(3);
    }

    @Test
    @DisplayName("AC-11 — the ticket expires the seconds the service said after the request was sent")
    void request_ticket_expiresWhenTheServiceSaid() {
        answers(created());

        assertThat(request(client()).expiresAt()).isEqualTo(NOW.plusSeconds(300));
    }

    @Test
    void request_addressWithTrailingSlash_postsToTheSamePath() {
        answers(created());

        client().request(SANDBOX, url() + "/", EMAIL, OFFER, EXPIRES_IN);

        assertThat(requests()).hasSize(1);
    }

    @Test
    @DisplayName("NFR-4 — the only credential the Wallet Unit Service is sent is the tenant's system token")
    void request_neverCarriesAnotherToken() {
        answers(created());

        request(client());

        assertThat(tokens.requests()).containsExactly(SANDBOX);
        assertThat(requests()).allSatisfy(sent -> {
            assertThat(sent.getHeaders().getHeader("Authorization").values()).containsExactly("Bearer token-1");
            assertThat(sent.getHeader("Cookie")).isNull();
        });
    }

    @Test
    @DisplayName("EC-05 — a 401 invalidates the tenant's system token and is not retried")
    void request_401_invalidatesTheSystemTokenAndIsNotRetried() {
        answers(status(401));

        OnboardingTicketException failure = failureOf(() -> request(client()));

        assertThat(failure.reason()).isEqualTo(Reason.NOT_AUTHORIZED);
        assertThat(tokens.invalidations()).containsExactly(SANDBOX);
        assertThat(requests()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 403, 404, 409, 429})
    @DisplayName("AC-12 — a 4xx is a rejection, never retried and never invalidates the token")
    void request_4xx_isRejectedWithoutRetry(int status) {
        answers(status(status));

        OnboardingTicketException failure = failureOf(() -> request(client()));

        assertThat(failure.reason()).isEqualTo(Reason.REJECTED);
        assertThat(requests()).hasSize(1);
        assertThat(tokens.invalidations()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 504})
    @DisplayName("AC-12 — a 5xx is unavailable, never retried")
    void request_5xx_isUnavailableWithoutRetry(int status) {
        answers(status(status));

        OnboardingTicketException failure = failureOf(() -> request(client()));

        assertThat(failure.reason()).isEqualTo(Reason.UNAVAILABLE);
        assertThat(requests()).hasSize(1);
        assertThat(tokens.invalidations()).isEmpty();
    }

    @Test
    @DisplayName("AC-12 — an answer that comes after the wait is unavailable, never retried")
    void request_noAnswerInTime_isUnavailableWithoutRetry() {
        answers(created().withFixedDelay(2_000));
        OnboardingTickets client = client(Duration.ofMillis(300));

        OnboardingTicketException failure = failureOf(() -> request(client));

        assertThat(failure.reason()).isEqualTo(Reason.UNAVAILABLE);
        assertThat(requests()).hasSize(1);
    }

    @Test
    @DisplayName("AC-12 — a connection error is unavailable, never retried")
    void request_networkError_isUnavailableWithoutRetry() {
        answers(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER));

        OnboardingTicketException failure = failureOf(() -> request(client()));

        assertThat(failure.reason()).isEqualTo(Reason.UNAVAILABLE);
        assertThat(requests()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"expiresIn\":300}",
            "{\"onboardingUrl\":\"" + ONBOARDING_URL + "\"}",
            "{\"onboardingUrl\":\" \",\"expiresIn\":300}",
            "{\"onboardingUrl\":\"" + ONBOARDING_URL + "\",\"expiresIn\":0}",
            "{\"onboardingUrl\":\"" + ONBOARDING_URL + "\",\"expiresIn\":-5}",
            "{\"onboardingUrl\":\"" + ONBOARDING_URL + "\",\"expiresIn\":\"soon\"}",
            "not json"})
    @DisplayName("AC-12 — a success without the address or the lifetime is an unexpected response")
    void request_successWithoutWhatTheTicketNeeds_isUnexpectedResponse(String body) {
        answers(created(body));

        OnboardingTicketException failure = failureOf(() -> request(client()));

        assertThat(failure.reason()).isEqualTo(Reason.UNEXPECTED_RESPONSE);
        assertThat(requests()).hasSize(1);
    }

    @Test
    @DisplayName("AC-12 — a success with no body is an unexpected response")
    void request_successWithoutBody_isUnexpectedResponse() {
        answers(aResponse().withStatus(201));

        assertThat(failureOf(() -> request(client())).reason()).isEqualTo(Reason.UNEXPECTED_RESPONSE);
    }

    @Test
    @DisplayName("AC-12 — without a system token the Wallet Unit Service is not asked")
    void request_noSystemToken_isNotAskedAndFails() {
        answers(created());
        tokens.failWith(SystemTokenUnavailableException.Reason.NO_MACHINE_CREDENTIAL);

        OnboardingTicketException failure = failureOf(() -> request(client()));

        assertThat(failure.reason()).isEqualTo(Reason.SYSTEM_TOKEN_UNAVAILABLE);
        assertThat(requests()).isEmpty();
    }

    @Test
    @DisplayName("NFR-3 — the result shows no address, token or personal data")
    void toString_ticket_showsNoSecret() {
        answers(created());

        assertThat(request(client()).toString()).doesNotContain(ONBOARDING_URL, "Zk3q9Xw2Lp7Rt4Vb8Nc1Ha", "token-1");
    }

    @Test
    @DisplayName("NFR-3 — no token, offer, address or email in logs or exceptions, on success or failure")
    void request_successAndFailures_leaveNothingInLogsOrExceptions() {
        walletUnitService.stubFor(post(urlEqualTo(PATH)).inScenario("tickets").whenScenarioStateIs("Started")
                .willReturn(created()).willSetStateTo("reset"));
        walletUnitService.stubFor(post(urlEqualTo(PATH)).inScenario("tickets").whenScenarioStateIs("reset")
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)).willSetStateTo("503"));
        walletUnitService.stubFor(post(urlEqualTo(PATH)).inScenario("tickets").whenScenarioStateIs("503")
                .willReturn(status(503)).willSetStateTo("400"));
        walletUnitService.stubFor(post(urlEqualTo(PATH)).inScenario("tickets").whenScenarioStateIs("400")
                .willReturn(status(400)).willSetStateTo("401"));
        walletUnitService.stubFor(post(urlEqualTo(PATH)).inScenario("tickets").whenScenarioStateIs("401")
                .willReturn(status(401)).willSetStateTo("empty"));
        walletUnitService.stubFor(post(urlEqualTo(PATH)).inScenario("tickets").whenScenarioStateIs("empty")
                .willReturn(created("{}")));
        OnboardingTickets client = client();
        List<Throwable> thrown = new ArrayList<>();
        request(client);
        for (int i = 0; i < 5; i++) {
            thrown.add(catchThrowable(() -> request(client)));
        }
        assertThat(thrown).allSatisfy(t -> assertThat(t).isInstanceOf(OnboardingTicketException.class));

        String everything = logs.list.stream().map(OnboardingTicketsIT::describe).collect(Collectors.joining("\n"))
                + "\n" + thrown.stream().map(OnboardingTicketsIT::describe).collect(Collectors.joining("\n"));
        assertThat(logs.list).isNotEmpty();
        assertThat(everything).doesNotContain(OFFER, "secret-9c1e", EMAIL, ONBOARDING_URL, "Zk3q9Xw2Lp7Rt4Vb8Nc1Ha",
                "token-", "Bearer");
    }

    private static String describe(ILoggingEvent event) {
        return event.getFormattedMessage() + describe(event.getThrowableProxy());
    }

    private static String describe(IThrowableProxy throwable) {
        return throwable == null ? "" : throwable.getClassName() + ":" + throwable.getMessage()
                + describe(throwable.getCause());
    }

    private static String describe(Throwable throwable) {
        return throwable == null ? "" : throwable.getClass().getName() + ":" + throwable.getMessage()
                + describe(throwable.getCause());
    }
}
