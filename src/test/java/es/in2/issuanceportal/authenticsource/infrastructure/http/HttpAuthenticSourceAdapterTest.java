package es.in2.issuanceportal.authenticsource.infrastructure.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import es.in2.issuanceportal.authenticsource.application.port.SourceSecrets;
import es.in2.issuanceportal.authenticsource.domain.Holder;
import es.in2.issuanceportal.authenticsource.domain.IdentifierType;
import es.in2.issuanceportal.authenticsource.domain.MismatchedField;
import es.in2.issuanceportal.authenticsource.domain.PersonalIdentifier;
import es.in2.issuanceportal.authenticsource.domain.RequestStatus;
import es.in2.issuanceportal.authenticsource.domain.SourceRequest;
import es.in2.issuanceportal.authenticsource.domain.SourceRequests;
import es.in2.issuanceportal.authenticsource.domain.config.InvalidSourceConfigException;
import es.in2.issuanceportal.authenticsource.domain.config.SourceAuth;
import es.in2.issuanceportal.authenticsource.domain.config.SourceContract;
import es.in2.issuanceportal.authenticsource.domain.config.SourceSettings;
import es.in2.issuanceportal.shared.systemtoken.SystemToken;
import es.in2.issuanceportal.shared.systemtoken.SystemTokenProvider;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.Socket;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SSLEngine;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The HTTP adapter against a local TLS server (WireMock on HTTPS) configured as the CGCOM adapter's contract 1.4.0
 * ({@code checkEligibility} and {@code disputePractitionerData}). The test trusts any certificate: production code
 * only ever uses the JVM's trust and HTTPS.
 */
@ExtendWith(OutputCaptureExtension.class)
class HttpAuthenticSourceAdapterTest {

    private static final TenantId CGCOM = new TenantId("cgcom");
    private static final String LOOKUP = "/api/v1/practitioners/eligibility";
    private static final String DISPUTE = "/api/v1/practitioners/data-disputes";
    private static final Holder HOLDER = new Holder("JOSÉ ANTONIO", "GARCÍA FERNÁNDEZ",
            new PersonalIdentifier(IdentifierType.PERSONAL_ID, "12345678Z"));

    /** The response example of {@code checkEligibility} in the contract 1.4.0 of the CGCOM adapter. */
    private static final String TWO_REQUESTS = """
            {"result": "eligible", "requests": [
              {"collegiate_number": "282812345", "status": "eligible", "renewal": false,
               "requested_by": "Hospital Ejemplo Norte", "requested_at": "2026-11-03T08:00:00Z",
               "data": {"givenName": "JOSE ANTONIO", "familyName": "GARCIA FERNANDEZ",
                        "personal_administrative_number": "282812345",
                        "medical_board": ["Colegio Oficial de Médicos de Madrid"],
                        "medical_speciality": ["Cardiología"], "legally_entitled": true},
               "evidence": {"authoritative_source": "cgcom-regcgcomval-cartera", "reference": "VdxxQ1KN...",
                            "checked_at": "2026-11-12T09:12:44Z"}},
              {"collegiate_number": "461234567", "status": "identity_mismatch", "mismatched_fields": ["given_name"],
               "renewal": false, "requested_by": "Centro Ejemplo Este", "requested_at": "2026-11-05T08:00:00Z",
               "data": {"givenName": "NO SE USA"},
               "evidence": {"authoritative_source": "cgcom-regcgcomval-cartera", "reference": "Ab12...",
                            "checked_at": "2026-11-12T09:12:45Z"}}]}""";

    private static WireMockServer server;
    private static SSLContext trustAnything;

    private final SystemTokenProvider tokens = new SystemTokenProvider() {
        @Override
        public SystemToken tokenFor(TenantId tenant) {
            return new SystemToken("system-token-of-" + tenant.value(), Instant.now().plusSeconds(60));
        }

        @Override
        public void invalidate(TenantId tenant) {
            // Intentionally empty: the fake token holder keeps nothing to invalidate.
        }
    };
    private final SourceSecrets secrets = (tenant, reference) -> "secret-of-" + reference;
    private final HttpAuthenticSourceAdapter adapter =
            new HttpAuthenticSourceAdapter(new ObjectMapper(), secrets, tokens, trustAnything());

    @BeforeAll
    static void startServer() {
        server = new WireMockServer(options().httpDisabled(true).dynamicHttpsPort());
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    @BeforeEach
    void resetServer() {
        server.resetAll();
    }

    private static SSLContext trustAnything() {
        if (trustAnything == null) {
            try {
                X509ExtendedTrustManager trustAll = new X509ExtendedTrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String type, Socket socket) {
                        // Intentionally empty: the test server's certificate is trusted without checks.
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String type, Socket socket) {
                        // Intentionally empty: the test server's certificate is trusted without checks.
                    }

                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String type, SSLEngine engine) {
                        // Intentionally empty: the test server's certificate is trusted without checks.
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String type, SSLEngine engine) {
                        // Intentionally empty: the test server's certificate is trusted without checks.
                    }

                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String type) {
                        // Intentionally empty: the test server's certificate is trusted without checks.
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String type) {
                        // Intentionally empty: the test server's certificate is trusted without checks.
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                };
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, new TrustManager[] {trustAll}, null);
                trustAnything = context;
            } catch (java.security.GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }
        return trustAnything;
    }

    private static SourceContract cgcomContract() {
        return new SourceContract(LOOKUP, DISPUTE,
                Map.of("given_name", "given_name", "family_names", "family_names",
                        "identifier", "administrative_number"),
                Map.of("given_name", "holder.given_name", "family_names", "holder.family_names",
                        "identifier", "holder.administrative_number", "reference", "collegiate_number"),
                Map.of("requests", "requests", "reason", "reason", "reference", "collegiate_number",
                        "status", "status", "mismatched_fields", "mismatched_fields", "renewal", "renewal",
                        "requested_by", "requested_by", "requested_at", "requested_at",
                        "display_identifier", "collegiate_number", "data", "data"),
                Map.of("reason", Map.of("not_found", "not_found", "no_active_enrollment", "no_requests"),
                        "status", Map.of("eligible", "available", "identity_mismatch", "identity_mismatch",
                                "identifier_mismatch", "identifier_mismatch"),
                        "mismatched_fields", Map.of("administrative_number", "identifier",
                                "given_name", "given_name", "family_names", "family_names")));
    }

    private SourceSettings settings(String url, SourceAuth auth, SourceContract contract) {
        return new SourceSettings("DoctorID", "http", url, auth == SourceAuth.SECRET ? "eudistack/cgcom/registro" : null,
                Duration.ofSeconds(2), Set.of(IdentifierType.PERSONAL_ID), List.of(), null, auth, contract, null);
    }

    private SourceSettings cgcom() {
        return settings("https://localhost:" + server.httpsPort(), SourceAuth.SYSTEM_TOKEN, cgcomContract());
    }

    private SourceRequests lookup(SourceSettings settings) {
        return adapter.lookup(CGCOM, "doctorid.sd.1", HOLDER, settings, Duration.ofSeconds(2));
    }

    private void answerLookup(int status, String body) {
        server.stubFor(post(urlEqualTo(LOOKUP)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    @Test
    @DisplayName("AC-19 — lookup_cgcomExample_translatesTheRequestsWithTheContractNames")
    void lookup_cgcomExample_translatesTheRequestsWithTheContractNames() {
        answerLookup(200, TWO_REQUESTS);

        SourceRequests answer = lookup(cgcom());

        List<SourceRequest> requests = ((SourceRequests.Requests) answer).requests();
        assertThat(requests).hasSize(2);
        SourceRequest first = requests.getFirst();
        assertThat(first.sourceRef()).isEqualTo("282812345");
        assertThat(first.displayIdentifier()).isEqualTo("282812345");
        assertThat(first.status()).isEqualTo(RequestStatus.AVAILABLE);
        assertThat(first.renewal()).isFalse();
        assertThat(first.requestedBy()).isEqualTo("Hospital Ejemplo Norte");
        assertThat(first.requestedAt()).isEqualTo(Instant.parse("2026-11-03T08:00:00Z"));
        assertThat(first.organization()).isNull();
        // Values as the source gives them: lists stay lists, booleans stay booleans.
        assertThat(first.attributes()).containsEntry("medical_board", List.of("Colegio Oficial de Médicos de Madrid"))
                .containsEntry("medical_speciality", List.of("Cardiología"))
                .containsEntry("legally_entitled", true)
                .containsEntry("personal_administrative_number", "282812345");
        SourceRequest second = requests.get(1);
        assertThat(second.sourceRef()).isEqualTo("461234567");
        assertThat(second.status()).isEqualTo(RequestStatus.IDENTITY_MISMATCH);
        assertThat(second.mismatchedFields()).containsExactly(MismatchedField.GIVEN_NAME);
        assertThat(second.attributes()).isEmpty();
    }

    @Test
    @DisplayName("AC-19 — lookup_identityInTheContractNames_withoutIdentifierTypeAndWithTheBearerToken")
    void lookup_identityInTheContractNames_withoutIdentifierTypeAndWithTheBearerToken() {
        answerLookup(200, TWO_REQUESTS);

        lookup(cgcom());

        server.verify(1, postRequestedFor(urlEqualTo(LOOKUP))
                .withHeader("Authorization", WireMock.equalTo("Bearer system-token-of-cgcom"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(equalToJson("""
                        {"given_name": "JOSÉ ANTONIO", "family_names": "GARCÍA FERNÁNDEZ",
                         "administrative_number": "12345678Z"}""", false, false)));
    }

    @Test
    @DisplayName("AC-19 — lookup_contractThatNamesTheIdentifierType_sendsIt")
    void lookup_contractThatNamesTheIdentifierType_sendsIt() {
        answerLookup(200, TWO_REQUESTS);
        SourceContract contract = new SourceContract(LOOKUP, null,
                Map.of("identifier", "id", "identifier_type", "id_kind"), null,
                cgcomContract().response(), cgcomContract().values());

        lookup(settings("https://localhost:" + server.httpsPort(), SourceAuth.SYSTEM_TOKEN, contract));

        server.verify(postRequestedFor(urlEqualTo(LOOKUP))
                .withRequestBody(equalToJson("{\"id\": \"12345678Z\", \"id_kind\": \"personal_id\"}", false, false)));
    }

    @Test
    @DisplayName("AC-19 — lookup_authSecret_sendsTheCredentialOfSecretRef")
    void lookup_authSecret_sendsTheCredentialOfSecretRef() {
        answerLookup(200, TWO_REQUESTS);

        lookup(settings("https://localhost:" + server.httpsPort(), SourceAuth.SECRET, cgcomContract()));

        server.verify(postRequestedFor(urlEqualTo(LOOKUP)).withHeader("Authorization",
                WireMock.equalTo("Bearer secret-of-eudistack/cgcom/registro")));
    }

    @Test
    @DisplayName("AC-19 — lookup_cgcomReasons_areNotFoundAndNoRequests")
    void lookup_cgcomReasons_areNotFoundAndNoRequests() {
        answerLookup(200, "{\"result\": \"not_eligible\", \"reason\": \"no_active_enrollment\", \"requests\": []}");
        assertThat(lookup(cgcom())).isInstanceOf(SourceRequests.NoRequests.class);

        answerLookup(200, "{\"result\": \"not_eligible\", \"reason\": \"not_found\", \"requests\": []}");
        assertThat(lookup(cgcom())).isInstanceOf(SourceRequests.NotFound.class);
    }

    @Test
    void lookup_emptyListWithoutReason_isNoRequests() {
        answerLookup(200, "{\"result\": \"not_eligible\", \"requests\": []}");

        assertThat(lookup(cgcom())).isInstanceOf(SourceRequests.NoRequests.class);
    }

    @Test
    @DisplayName("ES-04 — lookup_answerOfTheSourceDoesNotMatchTheContract_throws")
    void lookup_answerOfTheSourceDoesNotMatchTheContract_throws() {
        List<String> bodies = List.of(
                "not json",
                "[]",
                "{\"requests\": \"none\"}",
                "{\"result\": \"x\"}",
                "{\"reason\": \"something_else\", \"requests\": []}",
                "{\"requests\": [{\"collegiate_number\": \"1\", \"status\": \"gone_wrong\"}]}",
                "{\"requests\": [{\"collegiate_number\": \"1\"}]}",
                "{\"requests\": [{\"status\": \"eligible\"}]}",
                "{\"requests\": [{\"collegiate_number\": \"1\", \"status\": \"under_review\"}]}",
                "{\"requests\": [{\"collegiate_number\": \"1\", \"status\": \"identity_mismatch\","
                        + "\"mismatched_fields\": [\"shoe_size\"]}]}",
                "{\"requests\": [{\"collegiate_number\": \"1\", \"status\": \"eligible\", \"renewal\": \"yes\"}]}",
                "{\"requests\": [{\"collegiate_number\": \"1\", \"status\": \"eligible\", \"requested_at\": \"ayer\"}]}",
                "{\"requests\": [{\"collegiate_number\": \"1\", \"status\": \"eligible\", \"data\": [1]}]}");
        SourceSettings settings = cgcom();
        for (String body : bodies) {
            answerLookup(200, body);
            assertThatThrownBy(() -> lookup(settings)).as(body).isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    @DisplayName("ES-04 — lookup_statusOtherThan200_throws")
    void lookup_statusOtherThan200_throws() {
        for (int status : new int[] {204, 400, 401, 404, 500, 503}) {
            answerLookup(status, TWO_REQUESTS);
            assertThatThrownBy(() -> lookup(cgcom())).as("HTTP " + status)
                    .isInstanceOf(HttpAuthenticSourceAdapter.SourceCallException.class);
        }
    }

    @Test
    @DisplayName("ES-04 — lookup_redirection_isNotFollowedAndThrows")
    void lookup_redirection_isNotFollowedAndThrows() {
        server.stubFor(post(urlEqualTo(LOOKUP)).willReturn(aResponse().withStatus(307)
                .withHeader("Location", "https://localhost:" + server.httpsPort() + "/elsewhere")));
        server.stubFor(post(urlEqualTo("/elsewhere")).willReturn(aResponse().withStatus(200).withBody(TWO_REQUESTS)));

        assertThatThrownBy(() -> lookup(cgcom())).isInstanceOf(HttpAuthenticSourceAdapter.SourceCallException.class);
        server.verify(0, postRequestedFor(urlEqualTo("/elsewhere")));
    }

    @Test
    @DisplayName("ES-04 — lookup_urlWithoutHttps_isRejectedWhenTheConfigurationLoadsAndNeverCalled")
    void lookup_urlWithoutHttps_isRejectedWhenTheConfigurationLoadsAndNeverCalled() {
        // Rejected when the configuration loads (EUD-359 review F1); the adapter keeps its own https check.
        SourceContract contract = cgcomContract();
        String plainHttpUrl = "http://localhost:" + server.httpsPort();

        assertThatThrownBy(() -> settings(plainHttpUrl, SourceAuth.SYSTEM_TOKEN, contract))
                .isInstanceOf(InvalidSourceConfigException.class);
        assertThat(server.getAllServeEvents()).isEmpty();
    }

    @Test
    @DisplayName("AC-18 — lookup_sourceSlowerThanTheTimeout_throws")
    void lookup_sourceSlowerThanTheTimeout_throws() {
        server.stubFor(post(urlEqualTo(LOOKUP)).willReturn(aResponse().withStatus(200).withBody(TWO_REQUESTS)
                .withFixedDelay(3_000)));

        SourceSettings settings = cgcom();

        long start = System.nanoTime();
        assertThatThrownBy(() -> adapter.lookup(CGCOM, "doctorid.sd.1", HOLDER, settings, Duration.ofSeconds(1)))
                .isInstanceOf(RuntimeException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("NFR-4 — lookup_failures_logOnlyTheKindAndTheStatus")
    void lookup_failures_logOnlyTheKindAndTheStatus(CapturedOutput output) {
        answerLookup(500, "{\"detail\": \"JOSÉ ANTONIO 12345678Z 282812345\"}");

        SourceSettings settings = cgcom();

        assertThatThrownBy(() -> lookup(settings)).isInstanceOf(RuntimeException.class);

        assertThat(output.getAll()).contains("answered HTTP 500")
                .doesNotContain("12345678Z", "JOSÉ", "282812345", "GARCÍA", "system-token");
    }

    @Test
    @DisplayName("AC-19 — supportsDispute_onlyWithDisputePath")
    void supportsDispute_onlyWithDisputePath() {
        SourceContract noDispute = new SourceContract(LOOKUP, null, cgcomContract().request(), null,
                cgcomContract().response(), null);

        assertThat(adapter.supportsDispute(cgcom())).isTrue();
        assertThat(adapter.supportsDispute(settings("https://localhost:1", SourceAuth.SYSTEM_TOKEN, noDispute)))
                .isFalse();
    }

    @Test
    @DisplayName("AC-19 — dispute_sendsTheIdentityAndTheReferenceInTheContractNames")
    void dispute_sendsTheIdentityAndTheReferenceInTheContractNames() {
        server.stubFor(post(urlEqualTo(DISPUTE)).willReturn(aResponse().withStatus(204)));

        adapter.dispute(CGCOM, "doctorid.sd.1", HOLDER, "282812345", cgcom(), Duration.ofSeconds(2));

        server.verify(1, postRequestedFor(urlEqualTo(DISPUTE))
                .withHeader("Authorization", WireMock.equalTo("Bearer system-token-of-cgcom"))
                .withRequestBody(equalToJson("""
                        {"holder": {"given_name": "JOSÉ ANTONIO", "family_names": "GARCÍA FERNÁNDEZ",
                                    "administrative_number": "12345678Z"},
                         "collegiate_number": "282812345"}""", false, false)));
    }

    @Test
    @DisplayName("AC-19 — dispute_any2xx_isReceived")
    void dispute_any2xx_isReceived() {
        for (int status : new int[] {200, 201, 202, 204}) {
            server.stubFor(post(urlEqualTo(DISPUTE)).willReturn(aResponse().withStatus(status)));
            assertThatNoException().as("HTTP " + status).isThrownBy(() -> adapter.dispute(CGCOM, "doctorid.sd.1",
                    HOLDER, "282812345", cgcom(), Duration.ofSeconds(2)));
        }
    }

    @Test
    @DisplayName("AC-06 — dispute_notA2xx_throws")
    void dispute_notA2xx_throws() {
        for (int status : new int[] {302, 400, 404, 500}) {
            server.stubFor(post(urlEqualTo(DISPUTE)).willReturn(aResponse().withStatus(status)));
            assertThatThrownBy(() -> adapter.dispute(CGCOM, "doctorid.sd.1", HOLDER, "282812345", cgcom(),
                    Duration.ofSeconds(2))).as("HTTP " + status)
                    .isInstanceOf(HttpAuthenticSourceAdapter.SourceCallException.class);
        }
    }
}
