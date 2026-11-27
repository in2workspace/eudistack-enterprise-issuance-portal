package es.in2.issuanceportal.journey.infrastructure.systemtoken;

import com.eudistack.auditoria.autoconfigure.AuditoriaProperties;
import com.eudistack.auditoria.port.AuditAccessTokenProvider;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import es.in2.issuanceportal.MutableClock;
import es.in2.issuanceportal.shared.systemtoken.SystemTokenUnavailableException;
import es.in2.issuanceportal.shared.systemtoken.SystemTokenUnavailableException.Reason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The token for the Audit API against a simulated Verifier. */
class AuditTokenConfigurationTest {

    private static final String TOKEN_PATH = "/verifier/oidc/token";
    private static final String AUDIENCE = "http://verifier-service:8080/verifier";

    private static final MachineCredentialFixture FIXTURE = MachineCredentialFixture.create("platform");

    private WireMockServer verifier;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        verifier = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        verifier.start();
        clock = new MutableClock(Instant.parse("2026-11-26T10:00:00Z"));
    }

    @AfterEach
    void tearDown() {
        verifier.stop();
    }

    @Nested
    @DisplayName("AC-08 — asking the Verifier for the token")
    class Token {

        @Test
        void accessToken_firstCall_requestsItWithTheAuditAttestation() throws ParseException {
            // Arrange
            verifierGrants("token-1");
            AuditAccessTokenProvider provider = provider(settings());

            // Act
            String token = provider.accessToken(null);

            // Assert
            assertThat(token).isEqualTo("token-1");
            List<LoggedRequest> requests = tokenRequests();
            assertThat(requests).hasSize(1);
            LoggedRequest request = requests.get(0);
            assertThat(request.getHeader("X-Tenant")).isEqualTo("platform");
            assertThat(request.containsHeader("X-Forwarded-Host")).isFalse();
            assertThat(request.containsHeader("X-Forwarded-Proto")).isFalse();
            Map<String, String> form = form(request.getBodyAsString());
            assertThat(form).containsEntry("grant_type", "client_credentials")
                    .containsEntry("client_id", FIXTURE.clientId())
                    .containsEntry("client_assertion_type",
                            "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
            SignedJWT assertion = SignedJWT.parse(form.get("client_assertion"));
            JWTClaimsSet claims = assertion.getJWTClaimsSet();
            assertThat(claims.getIssuer()).isEqualTo(FIXTURE.clientId());
            assertThat(claims.getSubject()).isEqualTo(FIXTURE.clientId());
            assertThat(claims.getAudience()).containsExactly(AUDIENCE);
        }

        @Test
        void accessToken_firstCall_signsTheAssertionWithTheAttestationKey() throws Exception {
            // Arrange
            verifierGrants("token-1");
            AuditAccessTokenProvider provider = provider(settings());

            // Act
            provider.accessToken(null);

            // Assert
            SignedJWT assertion = SignedJWT.parse(
                    form(tokenRequests().get(0).getBodyAsString()).get("client_assertion"));
            assertThat(assertion.verify(new ECDSAVerifier(FIXTURE.key().toECPublicKey()))).isTrue();
        }

        @Test
        void accessToken_tokenStillValid_reusesIt() {
            // Arrange
            verifierGrants("token-1");
            AuditAccessTokenProvider provider = provider(settings());
            provider.accessToken(null);
            clock.advance(Duration.ofSeconds(869));

            // Act
            String token = provider.accessToken(null);

            // Assert
            assertThat(token).isEqualTo("token-1");
            assertThat(tokenRequests()).hasSize(1);
        }

        @Test
        void accessToken_tokenAboutToExpire_requestsAnother() {
            // Arrange
            verifierGrants("token-1");
            AuditAccessTokenProvider provider = provider(settings());
            provider.accessToken(null);
            clock.advance(Duration.ofSeconds(870));
            verifierGrants("token-2");

            // Act
            String token = provider.accessToken(null);

            // Assert
            assertThat(token).isEqualTo("token-2");
            assertThat(tokenRequests()).hasSize(2);
        }

        @Test
        void refreshAccessToken_tokenStillValid_requestsAnother() {
            // Arrange
            verifierGrants("token-1");
            AuditAccessTokenProvider provider = provider(settings());
            provider.accessToken(null);
            verifierGrants("token-2");

            // Act
            String token = provider.refreshAccessToken(null);

            // Assert
            assertThat(token).isEqualTo("token-2");
            assertThat(tokenRequests()).hasSize(2);
        }

        @ParameterizedTest
        @CsvSource({"401, VERIFIER_REJECTED", "503, VERIFIER_UNREACHABLE"})
        void accessToken_verifierFails_throwsUnavailableWithoutSecrets(int status, Reason reason) {
            // Arrange
            verifier.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse().withStatus(status)));
            AuditAccessTokenProvider provider = provider(settings());

            // Act & Assert
            assertThatThrownBy(() -> provider.accessToken(null))
                    .isInstanceOfSatisfying(SystemTokenUnavailableException.class,
                            e -> assertThat(e.reason()).isEqualTo(reason))
                    .hasMessageNotContaining(FIXTURE.credentialJwt());
        }

        @Test
        void accessToken_keyOfAnotherCredential_failsWithoutCallingTheVerifier() {
            // Arrange
            MachineCredentialFixture mismatched = MachineCredentialFixture.createMismatched("platform");
            AuditAccessTokenProvider provider = provider(settings()
                    .credential(mismatched.credentialJwt())
                    .privateKey(mismatched.key().toJSONString()));

            // Act & Assert
            assertThatThrownBy(() -> provider.accessToken(null))
                    .isInstanceOfSatisfying(SystemTokenUnavailableException.class,
                            e -> assertThat(e.reason()).isEqualTo(Reason.NO_MACHINE_CREDENTIAL));
            assertThat(verifier.findAll(anyRequestedFor(anyUrl()))).isEmpty();
        }
    }

    @Nested
    @DisplayName("AC-08 — settings that stop the start")
    class Settings {

        @ParameterizedTest
        @ValueSource(strings = {"AUDIT_M2M_TOKEN_URL", "AUDIT_M2M_TENANT", "AUDIT_M2M_ASSERTION_AUDIENCE",
                "AUDIT_M2M_CLIENT_ID", "AUDIT_M2M_CREDENTIAL", "AUDIT_M2M_PRIVATE_KEY"})
        void providerFor_missingSetting_failsNamingTheVariable(String variable) {
            // Arrange
            SettingsBuilder settings = settings().without(variable);

            // Act & Assert
            assertThatThrownBy(() -> provider(settings))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(variable + " is not set");
        }

        @Test
        void providerFor_invalidTenant_failsNamingTheVariable() {
            // Arrange
            SettingsBuilder settings = settings().tenant("not a tenant");

            // Act & Assert
            assertThatThrownBy(() -> provider(settings))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUDIT_M2M_TENANT");
        }

        @Test
        void providerFor_clientIdOfAnotherCredential_fails() {
            // Arrange
            SettingsBuilder settings = settings().clientId("did:key:other");

            // Act & Assert
            assertThatThrownBy(() -> provider(settings))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUDIT_M2M_CLIENT_ID");
        }

        @Test
        void providerFor_unusableCredential_failsWithoutQuotingIt() {
            // Arrange
            SettingsBuilder settings = settings().credential("secret-but-not-a-jwt");

            // Act & Assert
            assertThatThrownBy(() -> provider(settings))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUDIT_M2M_CREDENTIAL")
                    .hasMessageNotContaining("secret-but-not-a-jwt")
                    .hasNoCause();
        }

        @ParameterizedTest
        @ValueSource(strings = {"http://verifier-service:8080/verifier/oidc/token", "ftp://localhost/token"})
        void providerFor_insecureTokenUrl_fails(String tokenUrl) {
            // Arrange
            SettingsBuilder settings = settings().tokenUrl(tokenUrl);

            // Act & Assert
            assertThatThrownBy(() -> provider(settings))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AUDIT_M2M_TOKEN_URL must be https");
        }

        @Test
        void providerFor_relativeTokenUrl_fails() {
            // Arrange
            SettingsBuilder settings = settings().tokenUrl("/verifier/oidc/token");

            // Act & Assert
            assertThatThrownBy(() -> provider(settings))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("AUDIT_M2M_TOKEN_URL must be an absolute URL");
        }

        @ParameterizedTest
        @CsvSource({"https://verifier.example.org/token, false", "http://127.0.0.1:8080/token, false",
                "http://verifier-service:8080/token, true"})
        void providerFor_acceptedTokenUrl_returnsProvider(String tokenUrl, boolean allowInsecureHttp) {
            // Arrange
            AuditTokenProperties properties = settings().tokenUrl(tokenUrl).build();

            // Act
            AuditAccessTokenProvider provider =
                    AuditTokenConfiguration.providerFor(properties, allowInsecureHttp, clock);

            // Assert
            assertThat(provider).isNotNull();
        }

        @Test
        void toString_completeSettings_showsNoCredentialNorKey() {
            // Arrange
            AuditTokenProperties properties = settings().build();

            // Act
            String text = properties.toString();

            // Assert
            assertThat(text).doesNotContain(FIXTURE.credentialJwt(),
                    (String) FIXTURE.key().toJSONObject().get("d"));
        }
    }

    @Nested
    @DisplayName("AC-06, AC-08 — only with the audit dispatcher on")
    class Activation {

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(AuditTokenConfiguration.class)
                .withBean(AuditTokenProperties.class, () -> settings().without("AUDIT_M2M_CREDENTIAL").build())
                .withBean(AuditoriaProperties.class)
                .withBean(Clock.class, Clock::systemUTC);

        @Test
        void context_dispatcherOff_startsWithoutTokenProvider() {
            // Arrange
            ApplicationContextRunner off = runner.withPropertyValues("auditoria.dispatcher.enabled=false");

            // Act & Assert
            off.run(context -> assertThat(context).hasNotFailed()
                    .doesNotHaveBean(AuditAccessTokenProvider.class));
        }

        @Test
        void context_dispatcherOnWithMissingSetting_doesNotStart() {
            // Arrange
            ApplicationContextRunner on = runner.withPropertyValues("auditoria.dispatcher.enabled=true");

            // Act & Assert
            on.run(context -> assertThat(context).hasFailed().getFailure()
                    .rootCause().hasMessage("AUDIT_M2M_CREDENTIAL is not set"));
        }
    }

    private void verifierGrants(String token) {
        verifier.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"" + token + "\",\"token_type\":\"Bearer\",\"expires_in\":900}")));
    }

    private List<LoggedRequest> tokenRequests() {
        return verifier.findAll(postRequestedFor(urlEqualTo(TOKEN_PATH)));
    }

    private SettingsBuilder settings() {
        return new SettingsBuilder("http://localhost:" + verifier.port() + TOKEN_PATH);
    }

    private AuditAccessTokenProvider provider(SettingsBuilder settings) {
        return AuditTokenConfiguration.providerFor(settings.build(), false, clock);
    }

    private static Map<String, String> form(String body) {
        return Arrays.stream(body.split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(pair -> decode(pair[0]), pair -> decode(pair[1])));
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    /** Complete settings, with the values a scenario changes. */
    private static final class SettingsBuilder {

        private final Map<String, String> values = new HashMap<>();

        private SettingsBuilder(String tokenUrl) {
            values.put("AUDIT_M2M_TOKEN_URL", tokenUrl);
            values.put("AUDIT_M2M_TENANT", "platform");
            values.put("AUDIT_M2M_ASSERTION_AUDIENCE", AUDIENCE);
            values.put("AUDIT_M2M_CLIENT_ID", FIXTURE.clientId());
            values.put("AUDIT_M2M_CREDENTIAL", FIXTURE.credentialJwt());
            values.put("AUDIT_M2M_PRIVATE_KEY", FIXTURE.key().toJSONString());
        }

        SettingsBuilder tokenUrl(String value) {
            return with("AUDIT_M2M_TOKEN_URL", value);
        }

        SettingsBuilder tenant(String value) {
            return with("AUDIT_M2M_TENANT", value);
        }

        SettingsBuilder clientId(String value) {
            return with("AUDIT_M2M_CLIENT_ID", value);
        }

        SettingsBuilder credential(String value) {
            return with("AUDIT_M2M_CREDENTIAL", value);
        }

        SettingsBuilder privateKey(String value) {
            return with("AUDIT_M2M_PRIVATE_KEY", value);
        }

        SettingsBuilder without(String variable) {
            return with(variable, "");
        }

        private SettingsBuilder with(String variable, String value) {
            values.put(variable, value);
            return this;
        }

        AuditTokenProperties build() {
            return new AuditTokenProperties(values.get("AUDIT_M2M_TOKEN_URL"), values.get("AUDIT_M2M_TENANT"),
                    values.get("AUDIT_M2M_ASSERTION_AUDIENCE"), values.get("AUDIT_M2M_CLIENT_ID"),
                    values.get("AUDIT_M2M_CREDENTIAL"), values.get("AUDIT_M2M_PRIVATE_KEY"), Duration.ofSeconds(5));
        }
    }
}
