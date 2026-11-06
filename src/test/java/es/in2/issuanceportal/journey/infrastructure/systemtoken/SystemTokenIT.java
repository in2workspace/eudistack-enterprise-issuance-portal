package es.in2.issuanceportal.journey.infrastructure.systemtoken;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import es.in2.issuanceportal.MutableClock;
import es.in2.issuanceportal.journey.application.port.SystemToken;
import es.in2.issuanceportal.journey.application.port.SystemTokenProvider;
import es.in2.issuanceportal.journey.application.port.SystemTokenUnavailableException;
import es.in2.issuanceportal.journey.application.port.SystemTokenUnavailableException.Reason;
import es.in2.issuanceportal.journey.infrastructure.systemtoken.SystemTokenProperties.Provider;
import es.in2.issuanceportal.shared.tenant.PublicHostProperties;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The system token against a simulated Verifier: what the request carries, what happens when there is no
 * credential or the Verifier fails, and that nothing secret reaches the logs.
 */
class SystemTokenIT {

    private static final String TOKEN_PATH = "/verifier/oidc/token";
    private static final String ACCESS_TOKEN = "access-token-9f3c1d7a-system";
    private static final PublicHostProperties PUBLIC_HOSTS =
            new PublicHostProperties("{tenant}.127.0.0.1.nip.io:4443");
    private static final TenantId SANDBOX = new TenantId("sandbox");
    private static final TenantId KPMG = new TenantId("kpmg");
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path secrets;

    private WireMockServer verifier;
    private MutableClock clock;
    private ListAppender<ILoggingEvent> logs;
    private Logger rootLogger;

    @BeforeEach
    void setUp() {
        verifier = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        verifier.start();
        clock = new MutableClock(Instant.parse("2026-11-05T10:00:00Z"));
        logs = new ListAppender<>();
        logs.start();
        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(logs);
        verifier.stop();
    }

    private SystemTokenProvider provider(Duration timeout) {
        return SystemTokenConfiguration.providerFor(
                new SystemTokenProperties(Provider.FILE, null, secrets,
                        "http://localhost:" + verifier.port() + "/verifier", timeout),
                PUBLIC_HOSTS, clock, () -> mock(SsmClient.class));
    }

    private void verifierGrants() {
        verifier.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"" + ACCESS_TOKEN + "\",\"token_type\":\"Bearer\",\"expires_in\":900}")));
    }

    @Test
    @DisplayName("AC-05 — the request carries the tenant, the public host and a client assertion signed with the credential")
    void tokenFor_machineCredential_postsClientCredentialsToVerifier() throws Exception {
        MachineCredentialFixture fixture = MachineCredentialFixture.create("sandbox");
        fixture.writeTo(secrets, "sandbox");
        verifierGrants();

        SystemToken token = provider(Duration.ofSeconds(5)).tokenFor(SANDBOX);

        assertThat(token.value()).isEqualTo(ACCESS_TOKEN);
        assertThat(token.expiresAt()).isEqualTo(clock.instant().plusSeconds(900));
        List<LoggedRequest> requests = verifier.findAll(postRequestedFor(urlEqualTo(TOKEN_PATH)));
        assertThat(requests).hasSize(1);
        LoggedRequest request = requests.get(0);
        assertThat(request.getHeader("X-Tenant")).isEqualTo("sandbox");
        assertThat(request.getHeader("X-Forwarded-Host")).isEqualTo("sandbox.127.0.0.1.nip.io:4443");
        assertThat(request.getHeader("X-Forwarded-Proto")).isEqualTo("https");
        assertThat(request.getHeader("Content-Type")).startsWith("application/x-www-form-urlencoded");

        Map<String, String> form = form(request.getBodyAsString());
        assertThat(form).containsEntry("grant_type", "client_credentials")
                .containsEntry("client_id", fixture.clientId())
                .containsEntry("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");

        SignedJWT assertion = SignedJWT.parse(form.get("client_assertion"));
        assertThat(assertion.verify(new ECDSAVerifier(fixture.key().toECPublicKey()))).isTrue();
        assertThat(assertion.getHeader().getJWK()).isEqualTo(fixture.key().toPublicJWK());
        JWTClaimsSet claims = assertion.getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo(fixture.clientId());
        assertThat(claims.getSubject()).isEqualTo(fixture.clientId());
        assertThat(claims.getAudience()).containsExactly("https://sandbox.127.0.0.1.nip.io:4443/verifier");
        assertThat(claims.getJWTID()).isNotBlank();
        assertThat(claims.getExpirationTime().toInstant()).isAfter(clock.instant());

        SignedJWT presentation = SignedJWT.parse(new String(
                Base64.getDecoder().decode(claims.getStringClaim("vp_token")), StandardCharsets.US_ASCII));
        assertThat(presentation.verify(new ECDSAVerifier(fixture.key().toECPublicKey()))).isTrue();
        assertThat(presentation.getHeader().getJWK()).isEqualTo(fixture.key().toPublicJWK());
        assertThat(presentation.getJWTClaimsSet().getIssuer()).isEqualTo(fixture.clientId());
        JsonNode vp = JSON.valueToTree(presentation.getJWTClaimsSet().getJSONObjectClaim("vp"));
        assertThat(vp.get("verifiableCredential").get(0).asText()).isEqualTo(fixture.credentialJwt());
    }

    @Test
    @DisplayName("EC-05 — the token is reused until 30 s remain, then asked for again")
    void tokenFor_reusedUntilMargin_thenRequestedAgain() throws Exception {
        MachineCredentialFixture.create("sandbox").writeTo(secrets, "sandbox");
        verifierGrants();
        SystemTokenProvider provider = provider(Duration.ofSeconds(5));

        provider.tokenFor(SANDBOX);
        clock.advance(Duration.ofSeconds(869));
        provider.tokenFor(SANDBOX);
        assertThat(verifier.findAll(postRequestedFor(anyUrl()))).hasSize(1);

        clock.advance(Duration.ofSeconds(1));
        provider.tokenFor(SANDBOX);
        assertThat(verifier.findAll(postRequestedFor(anyUrl()))).hasSize(2);
    }

    @Test
    @DisplayName("EC-05 — each tenant is asked with its own credential and host, and keeps its own token")
    void tokenFor_twoTenants_eachWithItsOwnCredential() throws Exception {
        MachineCredentialFixture.create("sandbox").writeTo(secrets, "sandbox");
        MachineCredentialFixture.create("kpmg").writeTo(secrets, "kpmg");
        verifierGrants();
        SystemTokenProvider provider = provider(Duration.ofSeconds(5));

        provider.tokenFor(SANDBOX);
        provider.tokenFor(KPMG);
        provider.tokenFor(SANDBOX);
        provider.tokenFor(KPMG);

        List<LoggedRequest> requests = verifier.findAll(postRequestedFor(anyUrl()));
        assertThat(requests).extracting(r -> r.getHeader("X-Tenant")).containsExactly("sandbox", "kpmg");
        assertThat(requests).extracting(r -> form(r.getBodyAsString()).get("client_id"))
                .containsExactly("did:key:sandbox", "did:key:kpmg");
    }

    @Test
    @DisplayName("AC-05 — a tenant without machine credential gets no token and the Verifier is not called")
    void tokenFor_noMachineCredential_failsWithoutCallingVerifier() {
        verifierGrants();

        assertThatThrownBy(() -> provider(Duration.ofSeconds(5)).tokenFor(SANDBOX))
                .isInstanceOfSatisfying(SystemTokenUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.NO_MACHINE_CREDENTIAL));

        assertThat(verifier.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    @DisplayName("AC-05 — a secret that cannot be used counts as no machine credential")
    void tokenFor_unusableSecret_failsWithoutCallingVerifier() throws Exception {
        Files.writeString(secrets.resolve("sandbox.json"), "{\"clientId\":\"did:key:sandbox\"}");

        assertThatThrownBy(() -> provider(Duration.ofSeconds(5)).tokenFor(SANDBOX))
                .isInstanceOfSatisfying(SystemTokenUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.NO_MACHINE_CREDENTIAL));

        assertThat(verifier.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    @DisplayName("AC-05 — a key that is not the credential's cnf gives no token and the Verifier is not called")
    void tokenFor_keyDoesNotMatchCredential_failsWithoutCallingVerifier() throws Exception {
        MachineCredentialFixture mismatched = MachineCredentialFixture.createMismatched("sandbox");
        mismatched.writeTo(secrets, "sandbox");
        verifierGrants();

        Throwable failure = catchThrowable(() -> provider(Duration.ofSeconds(5)).tokenFor(SANDBOX));

        assertThat(failure).isInstanceOfSatisfying(SystemTokenUnavailableException.class,
                e -> assertThat(e.reason()).isEqualTo(Reason.NO_MACHINE_CREDENTIAL));
        assertThat(verifier.findAll(anyRequestedFor(anyUrl()))).isEmpty();
        String everything = logs.list.stream().map(SystemTokenIT::describe).collect(Collectors.joining("\n"))
                + describe(failure);
        assertThat(everything).doesNotContain(mismatched.credentialJwt(),
                (String) mismatched.key().toJSONObject().get("d"));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403})
    @DisplayName("ES-05 — the Verifier rejecting the client assertion gives no token")
    void tokenFor_verifierRejects_failsWithRejected(int status) throws Exception {
        MachineCredentialFixture.create("sandbox").writeTo(secrets, "sandbox");
        verifier.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody("{\"error\":\"invalid_client\"}")));

        assertThatThrownBy(() -> provider(Duration.ofSeconds(5)).tokenFor(SANDBOX))
                .isInstanceOfSatisfying(SystemTokenUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.VERIFIER_REJECTED));
    }

    @Test
    @DisplayName("ES-05 — a Verifier that fails gives no token")
    void tokenFor_verifierFails_failsWithUnreachable() throws Exception {
        MachineCredentialFixture.create("sandbox").writeTo(secrets, "sandbox");
        verifier.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> provider(Duration.ofSeconds(5)).tokenFor(SANDBOX))
                .isInstanceOfSatisfying(SystemTokenUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.VERIFIER_UNREACHABLE));
    }

    @Test
    @DisplayName("ES-05 — a Verifier that does not answer in time gives no token")
    void tokenFor_verifierTimesOut_failsWithUnreachable() throws Exception {
        MachineCredentialFixture.create("sandbox").writeTo(secrets, "sandbox");
        verifier.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse().withStatus(200).withFixedDelay(2_000)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"" + ACCESS_TOKEN + "\",\"expires_in\":900}")));

        assertThatThrownBy(() -> provider(Duration.ofMillis(300)).tokenFor(SANDBOX))
                .isInstanceOfSatisfying(SystemTokenUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.VERIFIER_UNREACHABLE));
    }

    @Test
    @DisplayName("ES-05 — a response without a usable token is a rejection")
    void tokenFor_responseWithoutToken_failsWithRejected() throws Exception {
        MachineCredentialFixture.create("sandbox").writeTo(secrets, "sandbox");
        verifier.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"token_type\":\"Bearer\"}")));

        assertThatThrownBy(() -> provider(Duration.ofSeconds(5)).tokenFor(SANDBOX))
                .isInstanceOfSatisfying(SystemTokenUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.VERIFIER_REJECTED));
    }

    @Test
    @DisplayName("NFR-3 — no credential, key, assertion or token in logs or exceptions, on success or failure")
    void tokenFor_successAndFailures_leaveNoSecretInLogsOrExceptions() throws Exception {
        MachineCredentialFixture sandbox = MachineCredentialFixture.create("sandbox");
        sandbox.writeTo(secrets, "sandbox");
        MachineCredentialFixture kpmg = MachineCredentialFixture.create("kpmg");
        kpmg.writeTo(secrets, "kpmg");
        verifier.stubFor(post(urlEqualTo(TOKEN_PATH)).inScenario("flow").whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"" + ACCESS_TOKEN + "\",\"expires_in\":900}"))
                .willSetStateTo("rejecting"));
        verifier.stubFor(post(urlEqualTo(TOKEN_PATH)).inScenario("flow").whenScenarioStateIs("rejecting")
                .willReturn(aResponse().withStatus(401).withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"invalid_client\",\"error_description\":\"" + ACCESS_TOKEN + "\"}")));
        SystemTokenProvider provider = provider(Duration.ofSeconds(5));

        provider.tokenFor(SANDBOX);
        Throwable rejected = catchThrowable(() -> provider.tokenFor(KPMG));
        Throwable missing = catchThrowable(
                () -> provider.tokenFor(new TenantId("cgcom")));
        assertThat(rejected).isInstanceOf(SystemTokenUnavailableException.class);
        assertThat(missing).isInstanceOf(SystemTokenUnavailableException.class);

        List<String> secretTexts = List.of(ACCESS_TOKEN, sandbox.credentialJwt(), kpmg.credentialJwt(),
                (String) sandbox.key().toJSONObject().get("d"), (String) kpmg.key().toJSONObject().get("d"),
                "client_assertion", "vp_token");
        String everything = logs.list.stream().map(SystemTokenIT::describe).collect(Collectors.joining("\n"))
                + "\n" + rejected + "\n" + missing + "\n" + describe(rejected) + describe(missing);
        assertThat(logs.list).isNotEmpty();
        assertThat(everything).doesNotContain(secretTexts.toArray(new String[0]));
        assertThat(provider.tokenFor(SANDBOX).toString()).doesNotContain(ACCESS_TOKEN);
    }

    @Test
    @DisplayName("SSM provider — reads the tenant's parameter from the store")
    void providerFor_ssm_readsTheTenantParameter() {
        SsmClient store = mock(SsmClient.class);
        when(store.getParameter(anyRequest())).thenThrow(ParameterNotFoundException.builder().build());
        SystemTokenProperties properties = new SystemTokenProperties(Provider.SSM,
                "/eudistack/dev/{tenant}/machine-credential", null, "http://localhost:" + verifier.port() + "/verifier",
                Duration.ofSeconds(5));

        SystemTokenProvider provider = SystemTokenConfiguration.providerFor(properties, PUBLIC_HOSTS, clock, () -> store);

        assertThatThrownBy(() -> provider.tokenFor(SANDBOX))
                .isInstanceOfSatisfying(SystemTokenUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.NO_MACHINE_CREDENTIAL));
        verify(store).getParameter(anyRequest());
    }

    @Test
    @DisplayName("File provider — the SSM client is never created")
    void providerFor_file_doesNotCreateSsmClient() {
        SystemTokenProperties properties = new SystemTokenProperties(Provider.FILE, null, secrets,
                "http://localhost:" + verifier.port() + "/verifier", Duration.ofSeconds(5));

        SystemTokenConfiguration.providerFor(properties, PUBLIC_HOSTS, clock, () -> {
            throw new AssertionError("No SSM client with the file provider");
        });
    }

    private static Map<String, String> form(String body) {
        return Arrays.stream(body.split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(pair -> decode(pair[0]), pair -> decode(pair[1])));
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
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

    private static Consumer<GetParameterRequest.Builder> anyRequest() {
        return org.mockito.ArgumentMatchers.any();
    }
}
