package es.in2.issuanceportal.journey.infrastructure.issuer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import es.in2.issuanceportal.journey.application.port.IssuanceResult;
import es.in2.issuanceportal.journey.application.port.IssuanceUnavailableException;
import es.in2.issuanceportal.journey.application.port.OfferNotRenewableException;
import es.in2.issuanceportal.shared.systemtoken.SystemTokenUnavailableException;
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
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The renewal of the offer against a simulated Issuer that answers as the branch EUD-295 of
 * {@code eudistack-core-issuer} does: {@code POST /api/v1/issuances/{id}/credential-offer}, 200 with the envelope
 * and no {@code Location}, 409 {@code offer_not_renewable}. What the request carries, the retries, how each answer
 * is read, and that nothing secret reaches the logs.
 */
class OfferRenewalApiIT {

    private static final TenantId SANDBOX = new TenantId("sandbox");
    private static final UUID PROCESS = UUID.fromString("0b3c4d5e-6f70-4812-9a3b-4c5d6e7f8091");
    private static final String PATH = "/issuer/api/v1/issuances/" + PROCESS + "/credential-offer";
    private static final String OFFER = "openid-credential-offer://?credential_offer_uri=https%3A%2F%2Fissuer.test%2Foffer%2Fsecret-renewed";
    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

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

    private IssuanceApi api(boolean retryOn503) {
        IssuerApiProperties properties = new IssuerApiProperties("http://localhost:" + issuer.port() + "/issuer",
                Duration.ofSeconds(5), Duration.ofMillis(1), retryOn503, 20, 50, 10, Duration.ofSeconds(30));
        return new IssuerApiConfiguration().issuanceApi(properties,
                new PublicHostProperties("{tenant}.127.0.0.1.nip.io:4443"), tokens, clock);
    }

    private static ResponseDefinitionBuilder renewed() {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"responses\":[{\"channel\":\"ui\",\"status\":200,"
                        + "\"body\":{\"credential_offer_uri\":\"" + OFFER + "\"}}]}");
    }

    private static ResponseDefinitionBuilder problem(int status, String type) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/problem+json")
                .withBody("{\"type\":\"" + type + "\",\"detail\":\"" + PROCESS + " " + OFFER + "\"}");
    }

    private void answers(ResponseDefinitionBuilder... responses) {
        for (int i = 0; i < responses.length; i++) {
            ScenarioMappingBuilder stub = post(urlEqualTo(PATH)).inScenario("renewal")
                    .whenScenarioStateIs(i == 0 ? Scenario.STARTED : "step-" + i).willReturn(responses[i]);
            if (i < responses.length - 1) {
                stub.willSetStateTo("step-" + (i + 1));
            }
            issuer.stubFor(stub);
        }
    }

    private List<LoggedRequest> requests() {
        return issuer.findAll(postRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("AC-07 — renewOffer_postsToTheProcessAsSystemWithoutKeyOrBody")
    void renewOffer_postsToTheProcessAsSystemWithoutKeyOrBody() {
        answers(renewed());

        api(false).renewOffer(SANDBOX, PROCESS);

        assertThat(requests()).singleElement().satisfies(sent -> {
            assertThat(sent.getUrl()).isEqualTo(PATH);
            assertThat(sent.getHeader("Authorization")).isEqualTo("Bearer token-1");
            assertThat(sent.getHeader("X-Tenant")).isEqualTo("sandbox");
            assertThat(sent.getHeader("X-Forwarded-Host")).isEqualTo("sandbox.127.0.0.1.nip.io:4443");
            assertThat(sent.getHeader("X-Forwarded-Proto")).isEqualTo("https");
            assertThat(sent.getHeader("X-Idempotency-Key")).isNull();
            assertThat(sent.getBodyAsString()).isEmpty();
        });
    }

    @Test
    @DisplayName("AC-07 — renewOffer_answerWithTheOffer_isTheSameProcessWithItsNewOfferAndTheDefaultValidity")
    void renewOffer_answerWithTheOffer_isTheSameProcessWithItsNewOfferAndTheDefaultValidity() {
        answers(renewed());

        IssuanceResult result = api(false).renewOffer(SANDBOX, PROCESS);

        assertThat(result.processId()).isEqualTo(PROCESS);
        assertThat(result.credentialOfferUri()).isEqualTo(OFFER);
        assertThat(result.offerExpiresAt()).isEqualTo(NOW.plus(RestClientIssuanceApi.DEFAULT_OFFER_VALIDITY));
    }

    @Test
    @DisplayName("AC-08 — renewOffer_409_isOfferNotRenewableWithoutRetries")
    void renewOffer_409_isOfferNotRenewableWithoutRetries() {
        answers(problem(409, "offer_not_renewable"));

        IssuanceApi issuanceApi = api(true);

        assertThatThrownBy(() -> issuanceApi.renewOffer(SANDBOX, PROCESS)).isInstanceOf(OfferNotRenewableException.class);

        assertThat(requests()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 403, 404, 422})
    @DisplayName("ES-07 — renewOffer_otherClientErrors_areRejectedWithoutRetries")
    void renewOffer_otherClientErrors_areRejectedWithoutRetries(int status) {
        answers(problem(status, "error"));

        IssuanceApi issuanceApi = api(true);

        assertThatThrownBy(() -> issuanceApi.renewOffer(SANDBOX, PROCESS)).isInstanceOfSatisfying(
                IssuanceRejectedException.class,
                e -> assertThat(e.reason()).isEqualTo(IssuanceRejectedException.Reason.REQUEST_REJECTED));

        assertThat(requests()).hasSize(1);
    }

    @Test
    @DisplayName("NFR-4 — renewOffer_401_isRejectedAndInvalidatesTheSystemToken")
    void renewOffer_401_isRejectedAndInvalidatesTheSystemToken() {
        answers(problem(401, "unauthorized"));

        IssuanceApi issuanceApi = api(false);

        assertThatThrownBy(() -> issuanceApi.renewOffer(SANDBOX, PROCESS)).isInstanceOfSatisfying(
                IssuanceRejectedException.class,
                e -> assertThat(e.reason()).isEqualTo(IssuanceRejectedException.Reason.NOT_AUTHORIZED));

        assertThat(tokens.invalidations()).containsExactly(SANDBOX);
    }

    @Test
    @DisplayName("ES-07 — renewOffer_503ThenSuccess_retriesLikeTheIssuance")
    void renewOffer_503ThenSuccess_retriesLikeTheIssuance() {
        answers(problem(503, "wallet_delivery_timeout"), renewed());

        IssuanceResult result = api(true).renewOffer(SANDBOX, PROCESS);

        assertThat(result.credentialOfferUri()).isEqualTo(OFFER);
        assertThat(requests()).hasSize(2);
    }

    @Test
    @DisplayName("ES-07 — renewOffer_503WithoutRetryOn503_isUnavailableAtOnce")
    void renewOffer_503WithoutRetryOn503_isUnavailableAtOnce() {
        answers(problem(503, "wallet_delivery_timeout"));

        IssuanceApi issuanceApi = api(false);

        assertThatThrownBy(() -> issuanceApi.renewOffer(SANDBOX, PROCESS)).isInstanceOf(IssuanceUnavailableException.class);

        assertThat(requests()).hasSize(1);
    }

    @Test
    @DisplayName("ES-07 — renewOffer_networkErrorThenSuccess_retries")
    void renewOffer_networkErrorThenSuccess_retries() {
        answers(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER), renewed());

        assertThat(api(false).renewOffer(SANDBOX, PROCESS).processId()).isEqualTo(PROCESS);
        assertThat(requests()).hasSize(2);
    }

    @Test
    @DisplayName("ES-07 — renewOffer_neverAnswers_stopsAfterThreeRetries")
    void renewOffer_neverAnswers_stopsAfterThreeRetries() {
        answers(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER));

        IssuanceApi issuanceApi = api(false);

        assertThatThrownBy(() -> issuanceApi.renewOffer(SANDBOX, PROCESS)).isInstanceOf(IssuanceUnavailableException.class);

        assertThat(requests()).hasSize(4);
    }

    @Test
    @DisplayName("ES-04 — renewOffer_answerWithoutTheOffer_isRejected")
    void renewOffer_answerWithoutTheOffer_isRejected() {
        answers(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"responses\":[{\"channel\":\"email\",\"status\":200}]}"));

        IssuanceApi issuanceApi = api(false);

        assertThatThrownBy(() -> issuanceApi.renewOffer(SANDBOX, PROCESS)).isInstanceOfSatisfying(
                IssuanceRejectedException.class,
                e -> assertThat(e.reason()).isEqualTo(IssuanceRejectedException.Reason.UNEXPECTED_RESPONSE));
    }

    @Test
    @DisplayName("NFR-4 — renewOffer_tenantWithoutSystemToken_failsWithoutCallingTheIssuer")
    void renewOffer_tenantWithoutSystemToken_failsWithoutCallingTheIssuer() {
        tokens.failWith(SystemTokenUnavailableException.Reason.NO_MACHINE_CREDENTIAL);

        IssuanceApi issuanceApi = api(false);

        assertThatThrownBy(() -> issuanceApi.renewOffer(SANDBOX, PROCESS))
                .isInstanceOf(SystemTokenUnavailableException.class);

        assertThat(requests()).isEmpty();
    }

    @Test
    @DisplayName("NFR-4 — renewOffer_everyOutcome_logsNeitherTheOfferNorTheProcessNorTheToken")
    void renewOffer_everyOutcome_logsNeitherTheOfferNorTheProcessNorTheToken() {
        answers(problem(409, "offer_not_renewable"));
        IssuanceApi issuanceApi = api(false);
        assertThatThrownBy(() -> issuanceApi.renewOffer(SANDBOX, PROCESS)).isInstanceOf(OfferNotRenewableException.class);
        issuer.resetAll();
        answers(renewed());
        api(false).renewOffer(SANDBOX, PROCESS);

        assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList())
                .noneMatch(message -> message.contains("secret-renewed") || message.contains("token-"));
    }
}
