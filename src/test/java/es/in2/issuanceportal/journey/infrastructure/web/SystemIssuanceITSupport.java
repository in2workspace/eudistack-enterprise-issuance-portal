package es.in2.issuanceportal.journey.infrastructure.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import es.in2.issuanceportal.TestcontainersConfiguration;
import es.in2.issuanceportal.journey.api.JourneyProgress;
import es.in2.issuanceportal.journey.application.port.SystemTokenProvider;
import es.in2.issuanceportal.journey.infrastructure.systemtoken.MachineCredentialFixture;
import es.in2.issuanceportal.session.api.EmailRecord;
import es.in2.issuanceportal.session.api.EmailVerificationRecord;
import es.in2.issuanceportal.session.api.EntitlementRecord;
import es.in2.issuanceportal.session.api.HolderIdentity;
import es.in2.issuanceportal.session.api.IdentifierType;
import es.in2.issuanceportal.session.api.JourneySessions;
import es.in2.issuanceportal.session.api.PersonalIdentifier;
import es.in2.issuanceportal.session.api.Section;
import es.in2.issuanceportal.session.api.SessionId;
import es.in2.issuanceportal.shared.tenant.TenantId;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The confirmation with the real HTTP clients of the service: the Verifier, the Issuer and the tenant's Wallet
 * Unit Service are one WireMock, PostgreSQL is a container and the tenant's machine credential is a file. The
 * Issuer answers like the real one to a repeated idempotency key: the process of an issuance is its key.
 */
@SpringBootTest(properties = {
        // Debug too, so that nothing hides in the lines that are not written by default (AC-15).
        "logging.level.es.in2.issuanceportal=DEBUG",
        "issuance-portal.issuance-api.retry-initial-wait=1ms"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
abstract class SystemIssuanceITSupport {

    static final String CONTEXT = "/issuance-portal";
    static final String SANDBOX_HOST = "sandbox.127.0.0.1.nip.io";
    static final TenantId SANDBOX = new TenantId("sandbox");

    static final String TOKEN_PATH = "/verifier/oidc/token";
    static final String ISSUANCES_PATH = "/issuer/api/v1/issuances";
    static final String TICKETS_PATH = "/wus/onboarding-tickets";

    static final String EMAIL = "elena.ruiz@example.test";
    static final String TOKEN_EMAIL = "elena.token@example.test";
    static final String GIVEN_NAME = "Elena";
    static final String FAMILY_NAME = "Ruiz Prieto";
    static final String DNI = "00000001R";
    static final String ADMINISTRATIVE_NUMBER = "280000001";
    static final String MEDICAL_BOARD = "Colegio de Medicos de Prueba";
    static final String SPECIALITY = "Cardiology";
    static final String ACCESS_TOKEN = "system-token-7e1d5c0a-secret";
    static final String HOLDER_TOKEN = "holder-access-token-2b9f41c6-secret";
    static final String OFFER = "openid-credential-offer://?credential_offer_uri=https%3A%2F%2Fissuer.test%2Foffers%2Fsecret-4d2e";
    static final String ONBOARDING_URL = "https://sandbox.wallet.test/t/ticket-fixed-3f2a9c";

    static final WireMockServer WIRE = startWireMock();
    static final Path SECRETS = secretsDirectory();
    static final MachineCredentialFixture MACHINE = MachineCredentialFixture.create("sandbox");

    private static final String CONFIG = """
            {"texts": {}, "legal_links": {}, "support": {}, "wallet_url": "https://sandbox.127.0.0.1.nip.io:4443/wallet",
             "attestation_type": "doctorid.sd.1",
             "data_placement": {
               "givenName": "identity.given_name",
               "familyName": "identity.family_name",
               "personal_administrative_number": "authentic_source.personal_administrative_number",
               "medical_board": "authentic_source.medical_board",
               "medical_speciality": "authentic_source.medical_speciality",
               "legally_entitled": "authentic_source.legally_entitled"},
             "onboarding": ONBOARDING}""";

    private static WireMockServer startWireMock() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort().globalTemplating(true));
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        return server;
    }

    private static Path secretsDirectory() {
        try {
            return Files.createTempDirectory("system-issuance-it");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void systems(DynamicPropertyRegistry registry) {
        registry.add("issuance-portal.system-token.verifier-internal-url", () -> "http://localhost:" + WIRE.port() + "/verifier");
        registry.add("issuance-portal.system-token.directory", SECRETS::toString);
        registry.add("issuance-portal.issuance-api.issuer-internal-url", () -> "http://localhost:" + WIRE.port() + "/issuer");
    }

    /** Keeps what the service logs, from any thread. */
    static final class CapturedLogs extends AppenderBase<ILoggingEvent> {

        private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            events.add(event);
        }

        List<ILoggingEvent> events() {
            return events;
        }

        /** Every line, with its exception chain and its MDC. */
        String text() {
            return events.stream().map(CapturedLogs::describe).collect(Collectors.joining("\n"));
        }

        /** The lines of the audit trail that carry this event. */
        List<String> auditLines(String event) {
            return events.stream().map(ILoggingEvent::getFormattedMessage)
                    .filter(line -> line.contains("event=" + event)).toList();
        }

        private static String describe(ILoggingEvent event) {
            return event.getFormattedMessage() + " " + event.getMDCPropertyMap() + describe(event.getThrowableProxy());
        }

        private static String describe(IThrowableProxy throwable) {
            return throwable == null ? "" : throwable.getClassName() + ":" + throwable.getMessage()
                    + describe(throwable.getCause());
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    JourneySessions sessions;
    @Autowired
    JourneyProgress progress;
    @Autowired
    SystemTokenProvider systemTokens;
    @Autowired
    Clock clock;

    CapturedLogs logs;
    private Logger rootLogger;

    @BeforeEach
    void setUpSystems() throws IOException {
        WIRE.resetAll();
        MACHINE.writeTo(SECRETS, "sandbox");
        // The tenant's token is cached for the context: each test starts without it.
        systemTokens.invalidate(SANDBOX);
        configure(false);
        logs = new CapturedLogs();
        logs.start();
        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLogger.addAppender(logs);
    }

    /** The container is shared with the other integration tests of the context, which expect no configuration. */
    @AfterEach
    void tearDownSystems() {
        rootLogger.detachAppender(logs);
        jdbc.sql("DELETE FROM \"sandbox_issuance_portal\".portal_config").update();
    }

    // --- the simulated systems ---

    void verifierGrants() {
        WIRE.stubFor(post(urlEqualTo(TOKEN_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"" + ACCESS_TOKEN + "\",\"token_type\":\"Bearer\",\"expires_in\":900}")));
    }

    /** The Location and the process are the idempotency key of the request: a repeated key gives the same process. */
    static ResponseDefinitionBuilder issued() {
        return aResponse().withStatus(200)
                .withHeader("Location", "https://" + SANDBOX_HOST + ":4443" + ISSUANCES_PATH
                        + "/{{request.headers.X-Idempotency-Key}}")
                .withHeader("Content-Type", "application/json")
                .withBody("{\"responses\":[{\"channel\":\"ui\",\"status\":200,"
                        + "\"body\":{\"credential_offer_uri\":\"" + OFFER + "\"}}]}");
    }

    void issuerIssues() {
        WIRE.stubFor(post(urlEqualTo(ISSUANCES_PATH)).willReturn(issued()));
    }

    void walletUnitServiceCreatesTicket() {
        WIRE.stubFor(post(urlEqualTo(TICKETS_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"onboardingUrl\":\"" + ONBOARDING_URL + "\",\"expiresIn\":540}")));
    }

    void everythingWorks() {
        verifierGrants();
        issuerIssues();
        walletUnitServiceCreatesTicket();
    }

    static List<LoggedRequest> requestsTo(String path) {
        return WIRE.findAll(postRequestedFor(urlEqualTo(path)));
    }

    static int requestsToAnySystem() {
        return WIRE.getAllServeEvents().size();
    }

    // --- the tenant ---

    /** The tenant's portal configuration, with the alta with ticket or without. */
    void configure(boolean onboarding) {
        String settings = onboarding
                ? "{\"enabled\": true, \"wallet_unit_service_url\": \"http://localhost:" + WIRE.port() + "/wus\"}"
                : "{\"enabled\": false}";
        configure(CONFIG.replace("ONBOARDING", settings));
    }

    void configure(String config) {
        jdbc.sql("INSERT INTO \"sandbox_issuance_portal\".portal_config (tenant, config) VALUES ('sandbox', CAST(:config AS jsonb)) "
                        + "ON CONFLICT (tenant) DO UPDATE SET config = EXCLUDED.config")
                .param("config", config)
                .update();
    }

    // --- the journey ---

    UUID journeyOf(SessionId session) {
        return sessions.journeyOf(SANDBOX, session).orElseThrow();
    }

    UUID processOf(SessionId session) {
        return jdbc.sql("SELECT issuance_process_id FROM \"sandbox_issuance_portal\".journey WHERE id = :id")
                .param("id", journeyOf(session)).query(UUID.class).optional().orElse(null);
    }

    Map<String, Object> rowOf(SessionId session) {
        return jdbc.sql("SELECT state, step, issuance_process_id FROM \"sandbox_issuance_portal\".journey WHERE id = :id")
                .param("id", journeyOf(session)).query().singleRow();
    }

    SessionId startedJourney() throws Exception {
        Cookie cookie = mvc.perform(MockMvcRequestBuilders.post(CONTEXT + "/api/v1/journey")
                        .contextPath(CONTEXT)
                        .header("X-Requested-With", "issuance-portal")
                        .with(host()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getCookie("journey_session");
        return SessionId.parse(cookie.getValue()).orElseThrow();
    }

    /** What Identification leaves in the journey. */
    SessionId identifiedJourney() throws Exception {
        SessionId session = startedJourney();
        sessions.write(SANDBOX, session, Section.IDENTITY, new HolderIdentity(GIVEN_NAME, FAMILY_NAME,
                new PersonalIdentifier(IdentifierType.DNI, DNI), TOKEN_EMAIL).toJson());
        progress.reach(SANDBOX, session, JourneyProgress.Step.IDENTIFIED);
        return session;
    }

    /** What the holder has before confirming: the entitlement and the email verified with a code. */
    SessionId entitledJourney() throws Exception {
        SessionId session = entitledJourneyWithoutEmail();
        sessions.write(SANDBOX, session, Section.EMAIL, new EmailRecord(EMAIL).toJson());
        sessions.write(SANDBOX, session, Section.EMAIL_VERIFICATION, new EmailVerificationRecord(EMAIL, null, null, 0, 1,
                clock.instant()).toJson());
        jdbc.sql("UPDATE \"sandbox_issuance_portal\".journey SET step = 'email_verified' WHERE id = :id")
                .param("id", journeyOf(session)).update();
        return session;
    }

    /** What Identification and Authentic source leave in the journey before the holder verifies the email. */
    SessionId entitledJourneyWithoutEmail() throws Exception {
        SessionId session = identifiedJourney();
        sessions.write(SANDBOX, session, Section.ENTITLEMENT, new EntitlementRecord(IdentifierType.DNI, Map.of(
                "personal_administrative_number", ADMINISTRATIVE_NUMBER,
                "medical_board", List.of(MEDICAL_BOARD),
                "medical_speciality", List.of(SPECIALITY),
                "legally_entitled", true),
                new EntitlementRecord.Preview("DoctorID", List.of(), null, true), clock.instant()).toJson());
        progress.reach(SANDBOX, session, JourneyProgress.Step.ENTITLEMENT_CONFIRMED);
        return session;
    }

    /** The holder's call, with the holder's own token: it must never go on to another system (NFR-4). It has no body. */
    static MockHttpServletRequestBuilder confirmation(SessionId session) {
        return MockMvcRequestBuilders.post(CONTEXT + "/api/v1/journey/confirmation")
                .contextPath(CONTEXT)
                .header("X-Requested-With", "issuance-portal")
                .header("Authorization", "Bearer " + HOLDER_TOKEN)
                .with(host())
                .cookie(new Cookie("journey_session", session.value()));
    }

    static RequestPostProcessor host() {
        return request -> {
            request.setServerName(SANDBOX_HOST);
            return request;
        };
    }

    /** NFR-4: whatever the Issuer, the Verifier and the Wallet Unit Service got, none of it is the holder's. */
    static void assertHolderTokenNeverSent() {
        assertThat(WIRE.getAllServeEvents()).allSatisfy(event -> {
            LoggedRequest request = event.getRequest();
            assertThat(request.getHeaders().all().toString()).doesNotContain(HOLDER_TOKEN);
            assertThat(request.getBodyAsString()).doesNotContain(HOLDER_TOKEN);
            assertThat(request.getUrl()).doesNotContain(HOLDER_TOKEN);
            assertThat(request.getHeader("Cookie")).isNull();
        });
        for (String path : List.of(ISSUANCES_PATH, TICKETS_PATH)) {
            // Some outcomes never reach the Issuer or the Wallet Unit Service: each request that did is checked.
            requestsTo(path).forEach(request ->
                    assertThat(request.getHeaders().getHeader("Authorization").values())
                            .containsExactly("Bearer " + ACCESS_TOKEN));
        }
    }
}
