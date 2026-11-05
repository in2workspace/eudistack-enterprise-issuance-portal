package es.in2.issuanceportal.journey.infrastructure.issuer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.ScenarioMappingBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import es.in2.issuanceportal.MutableClock;
import es.in2.issuanceportal.journey.InMemorySystemTokenProvider;
import es.in2.issuanceportal.journey.application.port.IssuanceApi;
import es.in2.issuanceportal.journey.application.port.IssuanceRejectedException;
import es.in2.issuanceportal.journey.application.port.IssuanceRejectedException.Reason;
import es.in2.issuanceportal.journey.application.port.IssuanceRequest;
import es.in2.issuanceportal.journey.application.port.IssuanceResult;
import es.in2.issuanceportal.journey.application.port.IssuanceUnavailableException;
import es.in2.issuanceportal.journey.application.port.SystemTokenUnavailableException;
import es.in2.issuanceportal.shared.tenant.PublicHostProperties;
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
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The issuance API client against a simulated Issuer: what the request carries, the retries and their limits,
 * how each answer is read, and that nothing personal or secret reaches the logs.
 */
class IssuanceApiIT {

    private static final String PATH = "/issuer/api/v1/issuances";
    private static final TenantId SANDBOX = new TenantId("sandbox");
    private static final UUID KEY = UUID.fromString("5b1f7c0e-3f2a-4b8e-9d51-0c2d7a6e4f10");
    private static final UUID PROCESS = UUID.fromString("0b3c4d5e-6f70-4812-9a3b-4c5d6e7f8091");
    private static final String OFFER = "openid-credential-offer://?credential_offer_uri=https%3A%2F%2Fissuer.test%2Foffer%2Fsecret-9c1e";
    private static final String EMAIL = "elena.ruiz@example.test";
    private static final String ADMINISTRATIVE_NUMBER = "28/1234567";
    private static final String GIVEN_NAME = "Elena";
    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    private WireMockServer issuer;
    private MutableClock clock;
    private InMemorySystemTokenProvider tokens;
    private ListAppender<ILoggingEvent> logs;
    private Logger rootLogger;

    @BeforeEach
    void setUp() {
        issuer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        issuer.start();
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
        issuer.stop();
    }

    private IssuanceApi api(boolean retryOn503, Duration timeout, Duration initialWait) {
        IssuerApiProperties properties = new IssuerApiProperties(
                "http://localhost:" + issuer.port() + "/issuer", timeout, initialWait, retryOn503);
        return new IssuerApiConfiguration().issuanceApi(properties,
                new PublicHostProperties("{tenant}.127.0.0.1.nip.io:4443"), tokens, clock);
    }

    private IssuanceApi api() {
        return api(false, Duration.ofSeconds(5), Duration.ofMillis(1));
    }

    private static IssuanceRequest request() {
        return new IssuanceRequest("doctorid.sd.1",
                Map.of("givenName", GIVEN_NAME, "personal_administrative_number", ADMINISTRATIVE_NUMBER),
                EMAIL, "ui", "authorization_code");
    }

    private static ResponseDefinitionBuilder issued() {
        return issued("https://sandbox.127.0.0.1.nip.io:4443" + PATH + "/" + PROCESS);
    }

    private static ResponseDefinitionBuilder issued(String location) {
        return aResponse().withStatus(200).withHeader("Location", location)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"responses\":[{\"channel\":\"ui\",\"status\":200,"
                        + "\"body\":{\"credential_offer_uri\":\"" + OFFER + "\"}}]}");
    }

    private static ResponseDefinitionBuilder status(int status) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/problem+json")
                .withBody("{\"type\":\"error\",\"detail\":\"" + EMAIL + " " + OFFER + "\"}");
    }

    private static ResponseDefinitionBuilder networkError() {
        return aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER);
    }

    /** The Issuer answers each call with the next response, and the last one from then on. */
    private void answers(ResponseDefinitionBuilder... responses) {
        for (int i = 0; i < responses.length; i++) {
            ScenarioMappingBuilder stub = post(urlEqualTo(PATH)).inScenario("issuance")
                    .whenScenarioStateIs(i == 0 ? Scenario.STARTED : "step-" + i)
                    .willReturn(responses[i]);
            if (i < responses.length - 1) {
                stub.willSetStateTo("step-" + (i + 1));
            }
            issuer.stubFor(stub);
        }
    }

    private List<LoggedRequest> requests() {
        return issuer.findAll(postRequestedFor(anyUrl()));
    }

    private static Throwable thrownBy(Runnable call) {
        return catchThrowable(call::run);
    }

    @Test
    @DisplayName("AC-06 — the request carries the system token, the tenant, the key and the public host, and the issuance")
    void issue_postsTheIssuanceAsSystem() throws Exception {
        answers(issued());

        IssuanceResult result = api().issue(SANDBOX, KEY, request());

        assertThat(result.processId()).isEqualTo(PROCESS);
        assertThat(result.credentialOfferUri()).isEqualTo(OFFER);
        assertThat(requests()).hasSize(1);
        LoggedRequest sent = requests().get(0);
        assertThat(sent.getHeader("Authorization")).isEqualTo("Bearer token-1");
        assertThat(sent.getHeader("X-Tenant")).isEqualTo("sandbox");
        assertThat(sent.getHeader("X-Idempotency-Key")).isEqualTo(KEY.toString());
        assertThat(sent.getHeader("X-Forwarded-Host")).isEqualTo("sandbox.127.0.0.1.nip.io:4443");
        assertThat(sent.getHeader("X-Forwarded-Proto")).isEqualTo("https");
        assertThat(sent.getHeader("Content-Type")).startsWith("application/json");
        JsonNode body = JSON.readTree(sent.getBodyAsString());
        assertThat(body.get("credential_configuration_id").asText()).isEqualTo("doctorid.sd.1");
        assertThat(body.get("email").asText()).isEqualTo(EMAIL);
        assertThat(body.get("delivery").asText()).isEqualTo("ui");
        assertThat(body.get("grant_type").asText()).isEqualTo("authorization_code");
        assertThat(body.get("payload").get("givenName").asText()).isEqualTo(GIVEN_NAME);
        assertThat(body.get("payload").get("personal_administrative_number").asText()).isEqualTo(ADMINISTRATIVE_NUMBER);
    }

    @Test
    @DisplayName("NFR-4 — the only credential the Issuer is sent is the tenant's system token")
    void issue_neverCarriesAnotherToken() {
        answers(issued());

        api().issue(SANDBOX, KEY, request());

        assertThat(tokens.requests()).containsExactly(SANDBOX);
        assertThat(requests()).allSatisfy(sent -> {
            assertThat(sent.getHeaders().getHeader("Authorization").values()).containsExactly("Bearer token-1");
            assertThat(sent.getHeader("Cookie")).isNull();
        });
    }

    @Test
    @DisplayName("AC-06 — after a network error the retry carries the same idempotency key")
    void issue_networkErrorThenSuccess_retriesWithTheSameKey() {
        answers(networkError(), issued());

        IssuanceResult result = api().issue(SANDBOX, KEY, request());

        assertThat(result.processId()).isEqualTo(PROCESS);
        assertThat(requests()).hasSize(2)
                .allSatisfy(sent -> assertThat(sent.getHeader("X-Idempotency-Key")).isEqualTo(KEY.toString()));
    }

    @Test
    @DisplayName("EC-07 — at most 3 retries, then the Issuer is unavailable")
    void issue_neverAnswers_stopsAfterThreeRetries() {
        answers(networkError());

        Throwable thrown = thrownBy(() -> api().issue(SANDBOX, KEY, request()));

        assertThat(thrown).isInstanceOfSatisfying(IssuanceUnavailableException.class,
                e -> assertThat(e.reason()).isEqualTo(IssuanceUnavailableException.Reason.NETWORK));
        assertThat(requests()).hasSize(4)
                .allSatisfy(sent -> assertThat(sent.getHeader("X-Idempotency-Key")).isEqualTo(KEY.toString()));
    }

    @Test
    @DisplayName("EC-07 — without Retry-After the waits are 1, 2 and 4 times the initial wait")
    void issue_noRetryAfter_waitsDouble() {
        answers(networkError());
        IssuanceApi api = api(false, Duration.ofSeconds(5), Duration.ofMillis(60));

        long start = System.nanoTime();
        assertThatThrownBy(() -> api.issue(SANDBOX, KEY, request())).isInstanceOf(IssuanceUnavailableException.class);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(elapsedMillis).isGreaterThanOrEqualTo(60 + 120 + 240 - 20);
    }

    @Test
    @DisplayName("EC-07 — a call that does not answer in time is retried, each with its own limit")
    void issue_timeout_isRetried() {
        answers(aResponse().withStatus(200).withFixedDelay(2_000), issued());

        IssuanceResult result = api(false, Duration.ofMillis(300), Duration.ofMillis(1)).issue(SANDBOX, KEY, request());

        assertThat(result.processId()).isEqualTo(PROCESS);
        assertThat(requests()).hasSize(2);
    }

    @Test
    @DisplayName("EC-07 — with the 503 retry on, the wait is the one Retry-After says")
    void issue_503WithRetryAfter_waitsForIt() {
        answers(status(503).withHeader("Retry-After", "1"), issued());
        IssuanceApi api = api(true, Duration.ofSeconds(5), Duration.ofMillis(1));

        long start = System.nanoTime();
        IssuanceResult result = api.issue(SANDBOX, KEY, request());
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(result.processId()).isEqualTo(PROCESS);
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(900);
        assertThat(requests()).hasSize(2)
                .allSatisfy(sent -> assertThat(sent.getHeader("X-Idempotency-Key")).isEqualTo(KEY.toString()));
    }

    @Test
    @DisplayName("EC-07 — with the 503 retry on, a 503 that never ends is unavailable after 3 retries")
    void issue_503Forever_withRetryOn_isUnavailableAfterThreeRetries() {
        answers(status(503).withHeader("Retry-After", "0"));

        Throwable thrown = thrownBy(() -> api(true, Duration.ofSeconds(5), Duration.ofMillis(1))
                .issue(SANDBOX, KEY, request()));

        assertThat(thrown).isInstanceOfSatisfying(IssuanceUnavailableException.class,
                e -> assertThat(e.reason()).isEqualTo(IssuanceUnavailableException.Reason.SERVER_ERROR));
        assertThat(requests()).hasSize(4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"11", "3600", "Thu, 05 Nov 2026 10:00:30 GMT"})
    @DisplayName("EC-07 — a Retry-After of more than 10 s ends the retries as unavailable, without waiting")
    void issue_503WithLongRetryAfter_isUnavailableWithoutRetry(String retryAfter) {
        answers(status(503).withHeader("Retry-After", retryAfter), issued());

        long start = System.nanoTime();
        Throwable thrown = thrownBy(() -> api(true, Duration.ofSeconds(5), Duration.ofMillis(1))
                .issue(SANDBOX, KEY, request()));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(thrown).isInstanceOfSatisfying(IssuanceUnavailableException.class,
                e -> assertThat(e.reason()).isEqualTo(IssuanceUnavailableException.Reason.SERVER_ERROR));
        assertThat(requests()).hasSize(1);
        assertThat(elapsedMillis).isLessThan(5_000);
    }

    @Test
    @DisplayName("EC-07 — by default a 503 is not retried")
    void issue_503_withRetryOff_isNotRetried() {
        answers(status(503), issued());

        Throwable thrown = thrownBy(() -> api().issue(SANDBOX, KEY, request()));

        assertThat(thrown).isInstanceOfSatisfying(IssuanceUnavailableException.class,
                e -> assertThat(e.reason()).isEqualTo(IssuanceUnavailableException.Reason.SERVER_ERROR));
        assertThat(requests()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 504})
    @DisplayName("ES-02 — any other 5xx is unavailable and not retried")
    void issue_otherServerError_isUnavailableWithoutRetry(int status) {
        answers(status(status), issued());

        Throwable thrown = thrownBy(() -> api(true, Duration.ofSeconds(5), Duration.ofMillis(1))
                .issue(SANDBOX, KEY, request()));

        assertThat(thrown).isInstanceOf(IssuanceUnavailableException.class);
        assertThat(requests()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 403, 404, 409, 422, 429})
    @DisplayName("ES-01 — a 4xx is a rejection and is not retried")
    void issue_clientError_isRejectedWithoutRetry(int status) {
        answers(status(status), issued());

        Throwable thrown = thrownBy(() -> api(true, Duration.ofSeconds(5), Duration.ofMillis(1))
                .issue(SANDBOX, KEY, request()));

        assertThat(thrown).isInstanceOfSatisfying(IssuanceRejectedException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.REQUEST_REJECTED));
        assertThat(requests()).hasSize(1);
        assertThat(tokens.invalidations()).isEmpty();
    }

    @Test
    @DisplayName("ES-01 — a 401 is a rejection, forgets the system token and is not retried")
    void issue_unauthorized_invalidatesTheTokenAndIsRejected() {
        answers(status(401), issued());

        Throwable thrown = thrownBy(() -> api(true, Duration.ofSeconds(5), Duration.ofMillis(1))
                .issue(SANDBOX, KEY, request()));

        assertThat(thrown).isInstanceOfSatisfying(IssuanceRejectedException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.NOT_AUTHORIZED));
        assertThat(requests()).hasSize(1);
        assertThat(tokens.invalidations()).containsExactly(SANDBOX);
    }

    @Test
    @DisplayName("AC-05 — a tenant without system token gets no request to the Issuer")
    void issue_noSystemToken_doesNotCallTheIssuer() {
        answers(issued());
        tokens.failWith(SystemTokenUnavailableException.Reason.NO_MACHINE_CREDENTIAL);

        assertThatThrownBy(() -> api().issue(SANDBOX, KEY, request()))
                .isInstanceOf(SystemTokenUnavailableException.class);

        assertThat(issuer.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    @DisplayName("ES-03 — a success without Location is a rejection")
    void issue_noLocation_isRejected() {
        answers(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"responses\":[{\"channel\":\"ui\",\"status\":200,\"body\":{\"credential_offer_uri\":\""
                        + OFFER + "\"}}]}"));

        assertThat(thrownBy(() -> api().issue(SANDBOX, KEY, request())))
                .isInstanceOfSatisfying(IssuanceRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.UNEXPECTED_RESPONSE));
        assertThat(requests()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://issuer.test/issuer/api/v1/issuances/not-a-uuid",
            "https://issuer.test/issuer/api/v1/issuances/",
            "https://issuer.test/issuer/api/v1/issuances/1-1-1-1-1",
            "https://issuer.test/issuer/api/v1/issuances/0b3c4d5e-6f70-4812-9a3b-4c5d6e7f8091/extra",
            "http://[broken"})
    @DisplayName("ES-03 — a Location that does not end in a process id is a rejection")
    void issue_locationNotAProcess_isRejected(String location) {
        answers(issued(location));

        assertThat(thrownBy(() -> api().issue(SANDBOX, KEY, request())))
                .isInstanceOfSatisfying(IssuanceRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.UNEXPECTED_RESPONSE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"responses\":[]}", "{\"responses\":[{\"channel\":\"ui\",\"status\":200}]}",
            "{\"responses\":[{\"channel\":\"ui\",\"status\":200,\"body\":{}}]}",
            "{\"responses\":[{\"channel\":\"ui\",\"status\":200,\"body\":{\"credential_offer_uri\":\" \"}}]}",
            "{}", "not json"})
    @DisplayName("ES-03 — a success without the credential offer is a rejection")
    void issue_noOffer_isRejected(String body) {
        answers(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withHeader("Location", "https://issuer.test" + PATH + "/" + PROCESS).withBody(body));

        assertThat(thrownBy(() -> api().issue(SANDBOX, KEY, request())))
                .isInstanceOfSatisfying(IssuanceRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.UNEXPECTED_RESPONSE));
        assertThat(requests()).hasSize(1);
    }

    @Test
    @DisplayName("AC-04 — a Location relative to the Issuer also gives the process")
    void issue_relativeLocation_givesTheProcess() {
        answers(issued("/issuer/api/v1/issuances/" + PROCESS));

        assertThat(api().issue(SANDBOX, KEY, request()).processId()).isEqualTo(PROCESS);
    }

    @Test
    @DisplayName("EC-06 — the offer expires 10 minutes after the response, since the Issuer gives no expiry")
    void issue_offer_expiresTenMinutesAfterTheResponse() {
        answers(issued());

        assertThat(api().issue(SANDBOX, KEY, request()).offerExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
    }

    @Test
    @DisplayName("NFR-3 — the result and the request show no offer, token or personal data")
    void toString_resultAndRequest_showNoSecretNorPersonalData() {
        answers(issued());

        String shown = api().issue(SANDBOX, KEY, request()) + " " + request();

        assertThat(shown).doesNotContain(OFFER, EMAIL, GIVEN_NAME, ADMINISTRATIVE_NUMBER, "token-1");
    }

    @Test
    @DisplayName("NFR-3 — no token, offer, email or payload value in logs or exceptions, on success or failure")
    void issue_successAndFailures_leaveNothingInLogsOrExceptions() {
        answers(issued(), networkError(), status(503), status(400), status(401),
                issued("https://issuer.test/not-a-process"));
        IssuanceApi api = api();
        List<Throwable> thrown = new ArrayList<>();
        api.issue(SANDBOX, KEY, request());
        for (int i = 0; i < 5; i++) {
            thrown.add(thrownBy(() -> api.issue(SANDBOX, KEY, request())));
        }
        assertThat(thrown).allSatisfy(t -> assertThat(t).isInstanceOfAny(
                IssuanceRejectedException.class, IssuanceUnavailableException.class));

        String everything = logs.list.stream().map(IssuanceApiIT::describe).collect(Collectors.joining("\n"))
                + "\n" + thrown.stream().map(IssuanceApiIT::describe).collect(Collectors.joining("\n"));
        assertThat(logs.list).isNotEmpty();
        assertThat(everything).doesNotContain(OFFER, "secret-9c1e", EMAIL, GIVEN_NAME, ADMINISTRATIVE_NUMBER,
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
