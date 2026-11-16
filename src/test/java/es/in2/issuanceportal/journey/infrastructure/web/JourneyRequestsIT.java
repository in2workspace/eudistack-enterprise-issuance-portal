package es.in2.issuanceportal.journey.infrastructure.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import es.in2.issuanceportal.TestcontainersConfiguration;
import es.in2.issuanceportal.journey.InMemoryOnboardingTickets;
import es.in2.issuanceportal.journey.api.JourneyProgress;
import es.in2.issuanceportal.session.api.EmailRecord;
import es.in2.issuanceportal.session.api.EmailVerificationRecord;
import es.in2.issuanceportal.session.api.EntitlementRecord;
import es.in2.issuanceportal.session.api.HolderIdentity;
import es.in2.issuanceportal.session.api.IdentifierType;
import es.in2.issuanceportal.session.api.IssuanceRecord;
import es.in2.issuanceportal.session.api.JourneySessions;
import es.in2.issuanceportal.session.api.PersonalIdentifier;
import es.in2.issuanceportal.session.api.Section;
import es.in2.issuanceportal.shared.security.HolderRefs;
import es.in2.issuanceportal.shared.security.TestHolder;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the integration tests of the operations on one request of the journey share: the Issuer in the hands of the
 * test, the sandbox tenant's configuration, and a journey with its email verified and the requests the test gives it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ConfirmationApiIT.Issuer.class})
abstract class JourneyRequestsIT {

    static final String CONTEXT = "/issuance-portal";
    static final String SANDBOX_HOST = "sandbox.127.0.0.1.nip.io";
    static final TenantId SANDBOX = new TenantId("sandbox");
    static final String EMAIL = "elena.ruiz@example.test";
    static final String CONFIG = """
            {"texts": {}, "legal_links": {}, "support": {}, "wallet_url": "https://sandbox.127.0.0.1.nip.io:4443/wallet",
             "attestation_type": "doctorid.sd.1",
             "data_placement": {
               "givenName": "identity.given_name",
               "familyName": "identity.family_name",
               "personal_administrative_number": "authentic_source.personal_administrative_number",
               "medical_board": "authentic_source.medical_board",
               "medical_speciality": "authentic_source.medical_speciality",
               "legally_entitled": "authentic_source.legally_entitled"},
             "onboarding": {"enabled": false}}""";
    static final String CONFIG_WITH_ONBOARDING = CONFIG.replace("\"onboarding\": {\"enabled\": false}",
            "\"onboarding\": {\"enabled\": true, \"wallet_unit_service_url\": "
                    + "\"https://sandbox.127.0.0.1.nip.io:4443/business-wallet/api/v1\"}");

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    JourneySessions sessions;
    @Autowired
    HolderRefs holderRefs;
    @Autowired
    JourneyProgress progress;
    @Autowired
    ConfirmationApiIT.IssuerGate issuer;
    @Autowired
    InMemoryOnboardingTickets tickets;
    @Autowired
    Clock clock;

    private ListAppender<ILoggingEvent> logs;
    private Logger rootLogger;

    @BeforeEach
    void setUpIssuerAndTenant() {
        issuer.reset();
        tickets.reset();
        configure(CONFIG);
        logs = new ListAppender<>();
        logs.start();
        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLogger.addAppender(logs);
    }

    /** The container is shared with the other integration tests, which expect no configuration. */
    @AfterEach
    void clearTenant() {
        rootLogger.detachAppender(logs);
        jdbc.sql("DELETE FROM \"sandbox_issuance_portal\".portal_config").update();
    }

    void configure(String config) {
        jdbc.sql("INSERT INTO \"sandbox_issuance_portal\".portal_config (tenant, config) VALUES ('sandbox', "
                        + "CAST(:config AS jsonb)) ON CONFLICT (tenant) DO UPDATE SET config = EXCLUDED.config")
                .param("config", config).update();
    }

    /** Everything the application logged since the test began. */
    List<String> logged() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    static EntitlementRecord.Request requestOf(String id, EntitlementRecord.Status status, String number) {
        boolean carriesData = status.carriesData();
        return new EntitlementRecord.Request(id, "SIM-" + id, status, List.of(), false, "Hospital", null, null, null,
                carriesData ? Map.of("personal_administrative_number", number,
                        "medical_board", List.of("Colegio de Medicos de Prueba"),
                        "medical_speciality", List.of("Cardiology"), "legally_entitled", true) : Map.of(),
                carriesData ? new EntitlementRecord.Preview("DoctorID", List.of()) : null);
    }

    /** A journey with its email verified and these requests. */
    TestHolder journeyWith(EntitlementRecord.Request... requests) throws Exception {
        TestHolder holder = TestHolder.of(SANDBOX, holderRefs);
        mvc.perform(MockMvcRequestBuilders.post(CONTEXT + "/api/v1/journey").contextPath(CONTEXT).with(host())
                .with(holder.token())).andExpect(status().isCreated());
        UUID journeyId = journeyOf(holder);
        sessions.write(SANDBOX, journeyId, Section.IDENTITY, new HolderIdentity("Elena", "Ruiz Prieto",
                new PersonalIdentifier(IdentifierType.PERSONAL_ID, "00000001R"), "elena.token@example.test").toJson());
        progress.reach(SANDBOX, holder.ref(), JourneyProgress.Step.IDENTIFIED);
        sessions.write(SANDBOX, journeyId, Section.ENTITLEMENT, new EntitlementRecord(IdentifierType.PERSONAL_ID,
                null, List.of(requests), clock.instant()).toJson());
        progress.reach(SANDBOX, holder.ref(), JourneyProgress.Step.ENTITLEMENT_CONFIRMED);
        sessions.write(SANDBOX, journeyId, Section.EMAIL, new EmailRecord(EMAIL).toJson());
        sessions.write(SANDBOX, journeyId, Section.EMAIL_VERIFICATION,
                new EmailVerificationRecord(EMAIL, null, null, 0, 1, clock.instant()).toJson());
        jdbc.sql("UPDATE \"sandbox_issuance_portal\".journey SET step = 'email_verified' WHERE id = :id")
                .param("id", journeyId).update();
        return holder;
    }

    UUID journeyOf(TestHolder holder) {
        return sessions.journeyOf(SANDBOX, holder.ref()).orElseThrow();
    }

    /** Makes the address delivered for the request expire: its offer and onboarding address end in the past. */
    void expireAddressOf(TestHolder holder, String requestId) {
        IssuanceRecord section = IssuanceRecord.fromJson(
                sessions.read(SANDBOX, journeyOf(holder), Section.ISSUANCE).orElseThrow());
        IssuanceRecord.Entry entry = section.entry(requestId).orElseThrow();
        Instant past = clock.instant().minusSeconds(60);
        sessions.write(SANDBOX, journeyOf(holder), Section.ISSUANCE, section.with(requestId,
                new IssuanceRecord.Entry(entry.idempotencyKey(), entry.email(), entry.credentialOfferUri(),
                        entry.credentialOfferUri() == null ? null : past, entry.onboardingUrl(),
                        entry.onboardingUrl() == null ? null : past.minusSeconds(60))).toJson());
    }

    IssuanceRecord.Entry entryOf(TestHolder holder, String requestId) {
        return IssuanceRecord.fromJson(sessions.read(SANDBOX, journeyOf(holder), Section.ISSUANCE).orElseThrow())
                .entry(requestId).orElseThrow();
    }

    MockHttpServletRequestBuilder post(TestHolder holder, String path, String body) {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.post(CONTEXT + "/api/v1" + path)
                .contextPath(CONTEXT).with(host()).with(holder.token());
        return body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    MockHttpServletRequestBuilder get(TestHolder holder, String path) {
        return MockMvcRequestBuilders.get(CONTEXT + "/api/v1" + path).contextPath(CONTEXT).with(host())
                .with(holder.token());
    }

    static String reference(String requestId) {
        return "{\"request_id\":\"" + requestId + "\"}";
    }

    static RequestPostProcessor host() {
        return request -> {
            request.setServerName(SANDBOX_HOST);
            return request;
        };
    }
}
