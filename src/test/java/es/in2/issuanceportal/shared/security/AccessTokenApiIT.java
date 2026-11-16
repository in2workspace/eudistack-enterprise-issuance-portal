package es.in2.issuanceportal.shared.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import es.in2.issuanceportal.TestcontainersConfiguration;
import es.in2.issuanceportal.journey.application.port.IncidentMailer;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The validation of the Access Token on every call of the journey, end to end against PostgreSQL and the simulated
 * Verifier: the scenarios of AC-05, AC-09, EC-01, EC-02, EC-07, ES-01 and ES-02. The tokens are minted here and the
 * tenant's real configuration is read (no automatic token, no fixed settings).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, AccessTokenApiIT.HolderProbe.class})
@TestPropertySource(properties = VerifierTokensConfiguration.AUTO_TOKEN + "=false")
class AccessTokenApiIT {

    private static final String CONTEXT = "/issuance-portal";
    private static final String API = CONTEXT + "/api/v1";
    private static final String SANDBOX_HOST = "sandbox.127.0.0.1.nip.io";
    private static final String KPMG_HOST = "kpmg.127.0.0.1.nip.io";
    private static final TenantId SANDBOX = new TenantId("sandbox");
    private static final TenantId KPMG = new TenantId("kpmg");
    private static final String CHALLENGE_INVALID = "Bearer error=\"invalid_token\"";

    private static final String CONFIG_JSON = """
            {
              "texts": {},
              "legal_links": {},
              "support": {"email": "soporte@example.org", "response_time": "48 horas"},
              "wallet_url": "https://%1$s.stg.eudistack.net/wallet",
              %2$s
              "identifier_types": {"doctorid.sd.1": "colegiado"}
            }""";

    /** The endpoints of the journey: every one needs a token. */
    enum Endpoint {
        POST_JOURNEY(HttpMethod.POST, "/journey"),
        GET_JOURNEY(HttpMethod.GET, "/journey"),
        WITHDRAWAL(HttpMethod.POST, "/journey/withdrawal"),
        ENTITLEMENT(HttpMethod.POST, "/entitlement"),
        DISPUTE(HttpMethod.POST, "/entitlement/requests/r1/dispute"),
        EMAIL_CODE(HttpMethod.POST, "/journey/email-verification"),
        EMAIL_CHECK(HttpMethod.POST, "/journey/email-verification/code"),
        CONFIRMATION(HttpMethod.POST, "/journey/confirmation"),
        OFFERS(HttpMethod.POST, "/journey/offers");

        final HttpMethod method;
        final String path;

        Endpoint(HttpMethod method, String path) {
            this.method = method;
            this.path = path;
        }
    }

    /** A token that must be rejected, and why. {@code mint} gets the current time. */
    record Rejected(String name, Function<Instant, String> mint) {

        @Override
        public String toString() {
            return name;
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private VerifierTokenDecoders decoders;
    @MockitoBean
    private IncidentMailer incidentMailer;

    private ListAppender<ILoggingEvent> logs;
    private final Map<String, Level> previousLevels = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        VerifierFixture.reset();
        decoders.invalidate(SANDBOX);
        decoders.invalidate(KPMG);
        save(SANDBOX, configJson("sandbox", true));
        save(KPMG, configJson("kpmg", true));
        // Everything the service and Spring Security would say, down to debug: what must not be in the log is
        // asserted against the most verbose log there can be.
        logs = new ListAppender<>();
        logs.start();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.addAppender(logs);
        for (String name : List.of("es.in2.issuanceportal", "org.springframework.security")) {
            Logger logger = (Logger) LoggerFactory.getLogger(name);
            previousLevels.put(name, logger.getLevel());
            logger.setLevel(Level.DEBUG);
        }
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
        previousLevels.forEach((name, level) -> ((Logger) LoggerFactory.getLogger(name)).setLevel(level));
        previousLevels.clear();
        jdbc.sql("DELETE FROM \"sandbox_issuance_portal\".portal_config").update();
        jdbc.sql("DELETE FROM \"kpmg_issuance_portal\".portal_config").update();
        VerifierFixture.reset();
    }

    // --- AC-05: the token is required, and a token that is not valid is rejected before anything is touched ---

    @ParameterizedTest
    @EnumSource(Endpoint.class)
    @DisplayName("AC-05 — journeyEndpoint_noToken_returns401WithBearerChallengeAndTouchesNothing")
    void journeyEndpoint_noToken_returns401WithBearerChallengeAndTouchesNothing(Endpoint endpoint) throws Exception {
        Map<String, Long> before = rows();

        mvc.perform(request(endpoint, SANDBOX_HOST, null))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.type").value("unauthenticated"));

        assertThat(rows()).isEqualTo(before);
    }

    static Stream<Rejected> rejectedTokens() {
        TenantId sandbox = new TenantId("sandbox");
        OctetSequenceKey hmacKey = new OctetSequenceKey.Builder(new byte[32]).keyID("oct-1").build();
        return Stream.of(
                new Rejected("expired beyond the clock skew", now -> AccessTokens.forTenant(sandbox, now)
                        .claim("iat", now.minus(Duration.ofHours(1))).claim("nbf", now.minus(Duration.ofHours(1)))
                        .claim("exp", now.minus(Duration.ofSeconds(45)))
                        .sign()),
                new Rejected("not yet valid beyond the clock skew", now -> AccessTokens.forTenant(sandbox, now)
                        .claim("nbf", now.plus(Duration.ofSeconds(45))).sign()),
                new Rejected("signed by another key", now -> AccessTokens.forTenant(sandbox, now)
                        .signedWith(VerifierFixture.newKey(VerifierFixture.KEY_ID)).sign()),
                new Rejected("signed by a key the Verifier does not publish", now -> AccessTokens
                        .forTenant(sandbox, now).signedWith(VerifierFixture.newKey("unknown-key")).sign()),
                new Rejected("alg none", now -> AccessTokens.forTenant(sandbox, now).unsecured()),
                new Rejected("HS256 with an oct key in the JWKS", now -> {
                    VerifierFixture.publish(VerifierFixture.signingKey(), hmacKey);
                    return AccessTokens.forTenant(sandbox, now).hmacSigned(hmacKey.toByteArray(), "oct-1");
                }),
                new Rejected("issuer of another Verifier", now -> AccessTokens.forTenant(sandbox, now)
                        .claim("iss", "https://other.example.org/verifier").sign()),
                new Rejected("audience without the portal client", now -> AccessTokens.forTenant(sandbox, now)
                        .claim("aud", List.of("another-client")).sign()),
                new Rejected("without audience", now -> AccessTokens.forTenant(sandbox, now).without("aud").sign()),
                new Rejected("tenant claim of another tenant", now -> AccessTokens.forTenant(sandbox, now)
                        .claim("tenant", "kpmg").sign()),
                new Rejected("acr substantial", now -> AccessTokens.forTenant(sandbox, now)
                        .claim("acr", AccessTokens.ACR_SUBSTANTIAL).sign()),
                new Rejected("without acr", now -> AccessTokens.forTenant(sandbox, now).without("acr").sign()),
                new Rejected("acr as an array of the right value", now -> AccessTokens.forTenant(sandbox, now)
                        .claim("acr", List.of(AccessTokens.ACR_HIGH)).sign()),
                new Rejected("tenant as an array of the right tenant", now -> AccessTokens.forTenant(sandbox, now)
                        .claim("tenant", List.of("sandbox")).sign()),
                new Rejected("ID token shape: no jti", now -> AccessTokens.forTenant(sandbox, now).without("jti").sign()),
                new Rejected("ID token shape: no jti, no tenant, with nonce and auth_time", now -> AccessTokens
                        .forTenant(sandbox, now).without("jti").without("tenant").claim("nonce", "n-0S6_WzA2Mj")
                        .claim("auth_time", now.minusSeconds(5)).sign()),
                new Rejected("with a jti but also a nonce", now -> AccessTokens.forTenant(sandbox, now)
                        .claim("nonce", "n-0S6_WzA2Mj").sign()),
                new Rejected("with a jti but also auth_time", now -> AccessTokens.forTenant(sandbox, now)
                        .claim("auth_time", now.minusSeconds(5)).sign()),
                new Rejected("without exp", now -> AccessTokens.forTenant(sandbox, now).without("exp").sign()),
                new Rejected("without iat", now -> AccessTokens.forTenant(sandbox, now).without("iat").sign()),
                new Rejected("not a JWT", now -> "not-a-token"));
    }

    @ParameterizedTest
    @MethodSource("rejectedTokens")
    @DisplayName("AC-05 — journeyEndpoints_invalidToken_return401WithInvalidTokenChallengeAndTouchNothing")
    void journeyEndpoints_invalidToken_return401WithInvalidTokenChallengeAndTouchNothing(Rejected rejected)
            throws Exception {
        String token = rejected.mint().apply(Instant.now());
        Map<String, Long> before = rows();

        for (Endpoint endpoint : Endpoint.values()) {
            mvc.perform(request(endpoint, SANDBOX_HOST, token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, CHALLENGE_INVALID))
                    .andExpect(jsonPath("$.type").value("unauthenticated"));
        }

        assertThat(rows()).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-05 — journeyEndpoint_bearerWithoutToken_returns401WithInvalidTokenChallenge")
    void journeyEndpoint_bearerWithoutToken_returns401WithInvalidTokenChallenge() throws Exception {
        mvc.perform(request(Endpoint.GET_JOURNEY, SANDBOX_HOST, null).header(HttpHeaders.AUTHORIZATION, "Bearer "))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, CHALLENGE_INVALID));
        mvc.perform(request(Endpoint.GET_JOURNEY, SANDBOX_HOST, null).header(HttpHeaders.AUTHORIZATION, "Basic YTpi"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
    }

    @Test
    @DisplayName("AC-05 — anyPathUnderApi_notListed_requiresToken")
    void anyPathUnderApi_notListed_requiresToken() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get(API + "/not-an-endpoint").contextPath(CONTEXT).with(host(SANDBOX_HOST)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("AC-05 — journeyEndpoint_validToken_passesTheValidation")
    void journeyEndpoint_validToken_passesTheValidation() throws Exception {
        String token = AccessTokens.forTenant(SANDBOX, Instant.now()).sign();

        // No journey for the holder yet: the call reached the controller, which answers 410, not the 401 of the validation.
        mvc.perform(request(Endpoint.GET_JOURNEY, SANDBOX_HOST, token))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.type").value("journey_closed"));
    }

    // --- AC-11 side of the contract: what stays open ---

    @Test
    @DisplayName("AC-04 — getConfig_noToken_isServed")
    void getConfig_noToken_isServed() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get(API + "/config").contextPath(CONTEXT).with(host(SANDBOX_HOST)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wallet_url").value("https://sandbox.stg.eudistack.net/wallet"));
    }

    @Test
    @DisplayName("AC-04 — postIncident_noToken_isAccepted")
    void postIncident_noToken_isAccepted() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post(API + "/incidents").contextPath(CONTEXT).with(host(SANDBOX_HOST))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Ana García", "email": "ana@example.org", "phone": "600 111 222",
                                 "type": "access", "subject": "No puedo entrar", "description": "Vuelvo al inicio."}"""))
                .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("AC-11 — operationalPaths_noToken_stayOpen")
    void operationalPaths_noToken_stayOpen() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get(CONTEXT + "/health").contextPath(CONTEXT))
                .andExpect(status().isOk());
        mvc.perform(MockMvcRequestBuilders.get(CONTEXT + "/health").contextPath(CONTEXT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer garbage"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("AC-05 — anyResponse_noSessionCreated")
    void anyResponse_noSessionCreated() throws Exception {
        mvc.perform(request(Endpoint.GET_JOURNEY, SANDBOX_HOST, null))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    // --- EC-01, EC-02, EC-07 ---

    @Test
    @DisplayName("EC-01 — validToken_keyRotatedByTheVerifier_reloadsTheJwksOnceForTheUnknownKid")
    void validToken_keyRotatedByTheVerifier_reloadsTheJwksOnceForTheUnknownKid() throws Exception {
        var rotated = VerifierFixture.newKey("verifier-key-2");
        mvc.perform(probe(AccessTokens.forTenant(SANDBOX, Instant.now()).sign())).andExpect(status().isOk());
        verifyJwksReads(1);

        VerifierFixture.publish(VerifierFixture.signingKey(), rotated);
        mvc.perform(probe(AccessTokens.forTenant(SANDBOX, Instant.now()).signedWith(rotated).sign()))
                .andExpect(status().isOk());
        verifyJwksReads(2);

        // Both keys are cached now: neither token reads the Verifier again.
        mvc.perform(probe(AccessTokens.forTenant(SANDBOX, Instant.now()).sign())).andExpect(status().isOk());
        mvc.perform(probe(AccessTokens.forTenant(SANDBOX, Instant.now()).signedWith(rotated).sign()))
                .andExpect(status().isOk());
        verifyJwksReads(2);
    }

    @Test
    @DisplayName("EC-01 — unknownKid_notPublishedAfterReload_returns401AfterOneReload")
    void unknownKid_notPublishedAfterReload_returns401AfterOneReload() throws Exception {
        mvc.perform(probe(AccessTokens.forTenant(SANDBOX, Instant.now()).sign())).andExpect(status().isOk());

        mvc.perform(probe(AccessTokens.forTenant(SANDBOX, Instant.now())
                        .signedWith(VerifierFixture.newKey("never-published")).sign()))
                .andExpect(status().isUnauthorized());

        verifyJwksReads(2);
    }

    @Test
    @DisplayName("EC-02 — token_expiredOrNotYetValidWithinTheClockSkew_isAccepted")
    void token_expiredOrNotYetValidWithinTheClockSkew_isAccepted() throws Exception {
        Instant now = Instant.now();
        String expired = AccessTokens.forTenant(SANDBOX, now).claim("iat", now.minus(Duration.ofHours(1)))
                .claim("nbf", now.minus(Duration.ofHours(1))).claim("exp", now.minus(Duration.ofSeconds(20))).sign();
        String notYet = AccessTokens.forTenant(SANDBOX, now).claim("nbf", now.plus(Duration.ofSeconds(20))).sign();

        mvc.perform(probe(expired)).andExpect(status().isOk());
        mvc.perform(probe(notYet)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("EC-07 — tokenOfSandbox_usedInKpmg_returns401AndTouchesNothing")
    void tokenOfSandbox_usedInKpmg_returns401AndTouchesNothing() throws Exception {
        String token = AccessTokens.forTenant(SANDBOX, Instant.now()).sign();
        Map<String, Long> before = rows();

        for (Endpoint endpoint : Endpoint.values()) {
            mvc.perform(request(endpoint, KPMG_HOST, token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, CHALLENGE_INVALID));
        }
        // And the other way round: the token of kpmg is no good in sandbox.
        mvc.perform(request(Endpoint.GET_JOURNEY, SANDBOX_HOST, AccessTokens.forTenant(KPMG, Instant.now()).sign()))
                .andExpect(status().isUnauthorized());

        assertThat(rows()).isEqualTo(before);
    }

    // --- AC-28: the level of assurance of the tenant ---

    @Test
    @DisplayName("AC-28 — tenantWithSubstantialLevel_acceptsSubstantialAndHighTokens")
    void tenantWithSubstantialLevel_acceptsSubstantialAndHighTokens() throws Exception {
        save(KPMG, withAssuranceLevel(configJson("kpmg", true), "substantial"));
        Instant now = Instant.now();

        mvc.perform(probe(AccessTokens.forTenant(KPMG, now).claim("acr", AccessTokens.ACR_SUBSTANTIAL).sign(),
                        KPMG_HOST))
                .andExpect(status().isOk());
        mvc.perform(probe(AccessTokens.forTenant(KPMG, now).sign(), KPMG_HOST)).andExpect(status().isOk());
        mvc.perform(probe(AccessTokens.forTenant(KPMG, now).without("acr").sign(), KPMG_HOST))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, CHALLENGE_INVALID));
        mvc.perform(probe(AccessTokens.forTenant(KPMG, now).claim("acr", "http://eidas.europa.eu/LoA/low").sign(),
                        KPMG_HOST))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("AC-28 — tenantWithHighLevel_rejectsSubstantialToken")
    void tenantWithHighLevel_rejectsSubstantialToken() throws Exception {
        save(KPMG, withAssuranceLevel(configJson("kpmg", true), "high"));

        mvc.perform(probe(AccessTokens.forTenant(KPMG, Instant.now()).claim("acr", AccessTokens.ACR_SUBSTANTIAL)
                        .sign(), KPMG_HOST))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, CHALLENGE_INVALID));
    }

    @Test
    @DisplayName("AC-28 — tenantWithUnknownLevel_isConfigUnavailableAndAcceptsNoToken")
    void tenantWithUnknownLevel_isConfigUnavailableAndAcceptsNoToken() throws Exception {
        save(KPMG, withAssuranceLevel(configJson("kpmg", true), "low"));

        mvc.perform(probe(AccessTokens.forTenant(KPMG, Instant.now()).sign(), KPMG_HOST))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("config_unavailable"));
    }

    // --- ES-01, ES-02 ---

    @Test
    @DisplayName("ES-01 — verifierDiscoveryDown_nothingCached_returns503AndRecoversWhenItIsBack")
    void verifierDiscoveryDown_nothingCached_returns503AndRecoversWhenItIsBack() throws Exception {
        VerifierFixture.wire().stubFor(get(urlPathEqualTo(VerifierFixture.DISCOVERY_PATH))
                .willReturn(aResponse().withStatus(500)));
        Map<String, Long> before = rows();

        mvc.perform(request(Endpoint.POST_JOURNEY, SANDBOX_HOST, AccessTokens.forTenant(SANDBOX, Instant.now()).sign()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("service_unavailable"))
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));

        assertThat(rows()).isEqualTo(before);
        // A failure is not cached: with the Verifier back, the same holder gets in.
        VerifierFixture.stubDiscovery();
        mvc.perform(probe(AccessTokens.forTenant(SANDBOX, Instant.now()).sign())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("ES-01 — verifierJwksDownOrBroken_nothingCached_returns503")
    void verifierJwksDownOrBroken_nothingCached_returns503() throws Exception {
        VerifierFixture.wire().stubFor(get(urlPathEqualTo(VerifierFixture.JWKS_PATH))
                .willReturn(aResponse().withStatus(503)));
        mvc.perform(probe(AccessTokens.forTenant(SANDBOX, Instant.now()).sign()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("service_unavailable"));

        VerifierFixture.wire().stubFor(get(urlPathEqualTo(VerifierFixture.JWKS_PATH))
                .willReturn(aResponse().withBody("<html>not a jwks</html>")));
        mvc.perform(probe(AccessTokens.forTenant(SANDBOX, Instant.now()).sign()))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("ES-01 — verifierDiscoveryWithoutJwksUri_returns503")
    void verifierDiscoveryWithoutJwksUri_returns503() throws Exception {
        VerifierFixture.wire().stubFor(get(urlPathEqualTo(VerifierFixture.DISCOVERY_PATH))
                .willReturn(aResponse().withBody("{\"issuer\":\"x\"}")));

        mvc.perform(probe(AccessTokens.forTenant(SANDBOX, Instant.now()).sign()))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("ES-01 — jwksRead_overTheInternalAddressWithTheTenantAndItsPublicHost")
    void jwksRead_overTheInternalAddressWithTheTenantAndItsPublicHost() throws Exception {
        mvc.perform(probe(AccessTokens.forTenant(SANDBOX, Instant.now()).sign())).andExpect(status().isOk());

        // The discovery names the public host in jwks_uri; only its path is used, and the headers say which tenant.
        VerifierFixture.wire().verify(1, getRequestedFor(urlPathEqualTo(VerifierFixture.DISCOVERY_PATH))
                .withHeader("X-Tenant", equalTo("sandbox"))
                .withHeader("X-Forwarded-Host", equalTo("sandbox.127.0.0.1.nip.io:4443"))
                .withHeader("X-Forwarded-Proto", equalTo("https")));
        VerifierFixture.wire().verify(1, getRequestedFor(urlPathEqualTo(VerifierFixture.JWKS_PATH))
                .withHeader("X-Tenant", equalTo("sandbox"))
                .withHeader("X-Forwarded-Host", equalTo("sandbox.127.0.0.1.nip.io:4443")));
    }

    @Test
    @DisplayName("ES-02 — tenantWithoutPortalClientId_returns503ConfigUnavailableAndWarns")
    void tenantWithoutPortalClientId_returns503ConfigUnavailableAndWarns() throws Exception {
        save(SANDBOX, configJson("sandbox", false));
        Map<String, Long> before = rows();

        mvc.perform(request(Endpoint.POST_JOURNEY, SANDBOX_HOST, AccessTokens.forTenant(SANDBOX, Instant.now()).sign()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("config_unavailable"));

        assertThat(rows()).isEqualTo(before);
        assertThat(warnings()).anyMatch(line -> line.contains("portal_client_id") && line.contains("sandbox"));
        // Another tenant is not affected.
        mvc.perform(request(Endpoint.GET_JOURNEY, KPMG_HOST, AccessTokens.forTenant(KPMG, Instant.now()).sign()))
                .andExpect(status().isGone());
    }

    @Test
    @DisplayName("ES-02 — tenantWithoutPortalClientId_invalidToken_returns401WithoutWarning")
    void tenantWithoutPortalClientId_invalidToken_returns401WithoutWarning() throws Exception {
        save(SANDBOX, configJson("sandbox", false));

        for (String token : List.of("not-a-token",
                AccessTokens.forTenant(SANDBOX, Instant.now()).signedWith(VerifierFixture.newKey(VerifierFixture.KEY_ID))
                        .sign(),
                AccessTokens.forTenant(SANDBOX, Instant.now()).without("jti").sign())) {
            mvc.perform(request(Endpoint.POST_JOURNEY, SANDBOX_HOST, token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, CHALLENGE_INVALID))
                    .andExpect(jsonPath("$.type").value("unauthenticated"));
        }

        assertThat(warnings()).noneMatch(line -> line.contains("portal_client_id"));
    }

    @Test
    @DisplayName("ES-01 — jwksUriWithAnotherHostInItsPath_returns503AndNoKeysAreRead")
    void jwksUriWithAnotherHostInItsPath_returns503AndNoKeysAreRead() throws Exception {
        for (String jwksUri : List.of("@evil.example/keys", "oidc/jwks", "//evil.example//keys", "")) {
            decoders.invalidate(SANDBOX);
            VerifierFixture.wire().stubFor(get(urlPathEqualTo(VerifierFixture.DISCOVERY_PATH))
                    .willReturn(aResponse().withBody("{\"jwks_uri\":\"" + jwksUri + "\"}")));

            mvc.perform(probe(AccessTokens.forTenant(SANDBOX, Instant.now()).sign()))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.type").value("service_unavailable"));
        }

        verifyJwksReads(0);
    }

    @Test
    @DisplayName("ES-01 — jwksUriOfAnotherHost_onlyItsPathIsReadFromTheInternalAddress")
    void jwksUriOfAnotherHost_onlyItsPathIsReadFromTheInternalAddress() throws Exception {
        VerifierFixture.wire().stubFor(get(urlPathEqualTo(VerifierFixture.DISCOVERY_PATH)).willReturn(aResponse()
                .withBody("{\"jwks_uri\":\"https://evil.example" + VerifierFixture.JWKS_PATH + "\"}")));

        mvc.perform(probe(AccessTokens.forTenant(SANDBOX, Instant.now()).sign())).andExpect(status().isOk());

        verifyJwksReads(1);
    }

    @Test
    @DisplayName("ES-02 — tenantWithoutPortalConfig_returns503ConfigUnavailable")
    void tenantWithoutPortalConfig_returns503ConfigUnavailable() throws Exception {
        jdbc.sql("DELETE FROM \"sandbox_issuance_portal\".portal_config").update();

        mvc.perform(request(Endpoint.GET_JOURNEY, SANDBOX_HOST, AccessTokens.forTenant(SANDBOX, Instant.now()).sign()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("config_unavailable"));
    }

    // --- AC-09: the personal identifier type follows the login method ---

    @Test
    @DisplayName("AC-09 — certificateLogin_noCredentialTypeAndHardwareOrSoftwareKey_isPersonalId")
    void certificateLogin_noCredentialTypeAndHardwareOrSoftwareKey_isPersonalId() throws Exception {
        for (String method : List.of("hwk", "swk")) {
            mvc.perform(probe(AccessTokens.forTenant(SANDBOX, Instant.now()).claim("amr", List.of(method)).sign()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.identifier_type").value("personal_id"));
        }
    }

    @Test
    @DisplayName("AC-09 — attestationLogin_credentialTypeMapped_isTheTypeOfPortalConfig")
    void attestationLogin_credentialTypeMapped_isTheTypeOfPortalConfig() throws Exception {
        String token = AccessTokens.forTenant(SANDBOX, Instant.now()).claim("credential_type", "doctorid.sd.1")
                .claim("amr", List.of("pwd")).sign();

        mvc.perform(probe(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identifier_type").value("colegiado"));
    }

    @Test
    @DisplayName("AC-09 — attestationLogin_credentialTypeWithCertificateMethod_isStillTheAttestationType")
    void attestationLogin_credentialTypeWithCertificateMethod_isStillTheAttestationType() throws Exception {
        String token = AccessTokens.forTenant(SANDBOX, Instant.now()).claim("credential_type", "doctorid.sd.1")
                .claim("amr", List.of("hwk")).sign();

        mvc.perform(probe(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identifier_type").value("colegiado"));
    }

    @Test
    @DisplayName("AC-09 — token_exposesTheHolderClaimsToTheController")
    void token_exposesTheHolderClaimsToTheController() throws Exception {
        AccessTokens token = AccessTokens.forTenant(SANDBOX, Instant.now());

        mvc.perform(probe(token.sign()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.iss").value(AccessTokens.issuerOf(SANDBOX)))
                .andExpect(jsonPath("$.sub").value(token.subject()))
                .andExpect(jsonPath("$.given_name").value(AccessTokens.GIVEN_NAME))
                .andExpect(jsonPath("$.family_name").value(AccessTokens.FAMILY_NAME))
                .andExpect(jsonPath("$.personal_administrative_number").value(AccessTokens.PERSONAL_NUMBER))
                .andExpect(jsonPath("$.amr[0]").value("hwk"))
                .andExpect(jsonPath("$.credential_type").doesNotExist());
    }

    @Test
    @DisplayName("AC-09 — token_withoutIdentityClaims_isValidAndTheyAreEmpty")
    void token_withoutIdentityClaims_isValidAndTheyAreEmpty() throws Exception {
        String token = AccessTokens.forTenant(SANDBOX, Instant.now()).without("given_name").without("family_name")
                .without("personal_administrative_number").sign();

        mvc.perform(probe(token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.given_name").doesNotExist())
                .andExpect(jsonPath("$.family_name").doesNotExist())
                .andExpect(jsonPath("$.personal_administrative_number").doesNotExist())
                .andExpect(jsonPath("$.identifier_type").value("personal_id"));
    }

    @Test
    @DisplayName("AC-09 — credentialTypeWithoutMapping_returns401AndWarnsOfTheConfiguration")
    void credentialTypeWithoutMapping_returns401AndWarnsOfTheConfiguration() throws Exception {
        String token = AccessTokens.forTenant(SANDBOX, Instant.now()).claim("credential_type", "other.sd.1").sign();

        mvc.perform(probe(token))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, CHALLENGE_INVALID))
                .andExpect(jsonPath("$.type").value("unauthenticated"));

        assertThat(warnings()).anyMatch(line -> line.contains("identifier_types") && line.contains("sandbox"));
    }

    @Test
    @DisplayName("AC-09 — noCredentialTypeAndNoCertificateMethod_returns401AndWarns")
    void noCredentialTypeAndNoCertificateMethod_returns401AndWarns() throws Exception {
        for (Function<AccessTokens, AccessTokens> other : List.<Function<AccessTokens, AccessTokens>>of(
                t -> t.claim("amr", List.of("pwd")), t -> t.without("amr"), t -> t.claim("amr", List.of()))) {
            logs.list.clear();

            mvc.perform(probe(other.apply(AccessTokens.forTenant(SANDBOX, Instant.now())).sign()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.type").value("unauthenticated"));

            assertThat(warnings()).anyMatch(line -> line.contains("identifier_types"));
        }
    }

    // --- NFR-2: nothing of the token or the holder in the log ---

    @Test
    @DisplayName("NFR-2 — rejectionsAndConfigurationFailures_logNothingOfTheTokenOrTheHolder")
    void rejectionsAndConfigurationFailures_logNothingOfTheTokenOrTheHolder() throws Exception {
        Instant now = Instant.now();
        AccessTokens valid = AccessTokens.forTenant(SANDBOX, now);
        List<AccessTokens> rejected = List.of(
                AccessTokens.forTenant(SANDBOX, now).claim("iat", now.minus(Duration.ofHours(1)))
                        .claim("exp", now.minus(Duration.ofMinutes(5))),
                AccessTokens.forTenant(SANDBOX, now).claim("acr", AccessTokens.ACR_SUBSTANTIAL),
                AccessTokens.forTenant(SANDBOX, now).claim("tenant", "kpmg"),
                AccessTokens.forTenant(SANDBOX, now).claim("aud", List.of("another-client")),
                AccessTokens.forTenant(SANDBOX, now).claim("credential_type", "other.sd.1"),
                AccessTokens.forTenant(SANDBOX, now).claim("amr", List.of("pwd")),
                AccessTokens.forTenant(SANDBOX, now).signedWith(VerifierFixture.newKey(VerifierFixture.KEY_ID)));
        List<String> secrets = new java.util.ArrayList<>(List.of(AccessTokens.GIVEN_NAME, AccessTokens.FAMILY_NAME,
                AccessTokens.PERSONAL_NUMBER));
        for (AccessTokens holder : Stream.concat(Stream.of(valid), rejected.stream()).toList()) {
            String token = holder.sign();
            secrets.add(token);
            secrets.add(token.split("[.]")[1]);
            secrets.add(holder.subject());
            if (holder != valid) {
                mvc.perform(probe(token)).andExpect(status().isUnauthorized());
            }
        }
        // The configuration failure and the dependency failure, too.
        save(SANDBOX, configJson("sandbox", false));
        mvc.perform(probe(valid.sign())).andExpect(status().isServiceUnavailable());
        save(SANDBOX, configJson("sandbox", true));
        decoders.invalidate(SANDBOX);
        VerifierFixture.wire().stubFor(get(urlPathEqualTo(VerifierFixture.DISCOVERY_PATH))
                .willReturn(aResponse().withStatus(500)));
        mvc.perform(probe(valid.sign())).andExpect(status().isServiceUnavailable());

        assertThat(logs.list).isNotEmpty();
        String log = logs.list.stream().map(AccessTokenApiIT::describe).reduce("", (a, b) -> a + "\n" + b);
        assertThat(log).doesNotContain(secrets);
    }

    // --- the holder is passed to the controller, not the token ---

    @Test
    @DisplayName("AC-09 — holderPrincipal_textForm_carriesNoPersonalData")
    void holderPrincipal_textForm_carriesNoPersonalData() {
        HolderPrincipal holder = new HolderPrincipal("https://v/verifier", "sub-1", "Lucía", "Prueba", "00000001R",
                null, List.of("hwk"), es.in2.issuanceportal.session.api.IdentifierType.PERSONAL_ID);

        assertThat(holder.toString()).doesNotContain("sub-1", "Lucía", "Prueba", "00000001R", "verifier");
        assertThat(new HolderAuthentication(holder).getName()).isEqualTo("holder");
        assertThat(new HolderAuthentication(holder).getCredentials()).isNull();
    }

    // --- helpers ---

    /** An endpoint of the journey as nginx forwards it, with the bearer token if there is one. */
    private static MockHttpServletRequestBuilder request(Endpoint endpoint, String host, String token) {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(endpoint.method, API + endpoint.path)
                .contextPath(CONTEXT)
                .with(host(host));
        if (endpoint.method == HttpMethod.POST) {
            request.contentType(MediaType.APPLICATION_JSON).content("{}");
        }
        return token == null ? request : request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static MockHttpServletRequestBuilder probe(String token) {
        return probe(token, SANDBOX_HOST);
    }

    private static MockHttpServletRequestBuilder probe(String token, String host) {
        return MockMvcRequestBuilders.get(API + "/probe/holder").contextPath(CONTEXT).with(host(host))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor host(String host) {
        return request -> {
            request.setServerName(host);
            return request;
        };
    }

    private static String configJson(String tenant, boolean withClientId) {
        return CONFIG_JSON.formatted(tenant,
                withClientId ? "\"portal_client_id\": \"" + AccessTokens.clientOf(new TenantId(tenant)) + "\"," : "");
    }

    private static String withAssuranceLevel(String config, String level) {
        return config.replace("\"identifier_types\"", "\"assurance_level\": \"" + level + "\",\n  \"identifier_types\"");
    }

    private void save(TenantId tenant, String config) {
        jdbc.sql("INSERT INTO " + tenant.schema() + ".portal_config (tenant, config) VALUES (:tenant, CAST(:config AS jsonb)) "
                        + "ON CONFLICT (tenant) DO UPDATE SET config = EXCLUDED.config, updated_at = now()")
                .param("tenant", tenant.value())
                .param("config", config)
                .update();
    }

    /** What the journey tables hold, to show a rejected call changed nothing. */
    private Map<String, Long> rows() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : List.of("journey", "journey_session")) {
            counts.put(table, jdbc.sql("SELECT count(*) FROM \"sandbox_issuance_portal\"." + table)
                    .query(Long.class).single());
        }
        return counts;
    }

    private void verifyJwksReads(int expected) {
        VerifierFixture.wire().verify(expected, getRequestedFor(urlPathEqualTo(VerifierFixture.JWKS_PATH)));
    }

    private List<String> warnings() {
        return logs.list.stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** The line, its MDC and its exception chain. */
    private static String describe(ILoggingEvent event) {
        return event.getFormattedMessage() + " " + event.getMDCPropertyMap() + describe(event.getThrowableProxy());
    }

    private static String describe(IThrowableProxy throwable) {
        return throwable == null ? "" : throwable.getClassName() + ":" + throwable.getMessage()
                + describe(throwable.getCause());
    }

    /** Hands back what the token validation gives a controller, which is not reachable otherwise in this task. */
    @RestController
    static class HolderProbe {

        @GetMapping("/api/v1/probe/holder")
        Map<String, Object> holder(@AuthenticationPrincipal HolderPrincipal holder) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("iss", holder.issuer());
            body.put("sub", holder.subject());
            body.put("given_name", holder.givenName());
            body.put("family_name", holder.familyName());
            body.put("personal_administrative_number", holder.personalAdministrativeNumber());
            body.put("credential_type", holder.credentialType());
            body.put("amr", holder.authenticationMethods());
            body.put("identifier_type", holder.personalIdentifierType().code());
            return body;
        }
    }
}
