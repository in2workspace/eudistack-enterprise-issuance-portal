package es.in2.issuanceportal.shared.security;

import es.in2.issuanceportal.TestcontainersConfiguration;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The JWKS reload limit, on a context where it is on (the other tests turn it off): a token with a kid the cache
 * does not have, inside the reload window, is a token that is not valid (401), never a Verifier that is down (503).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {VerifierTokensConfiguration.AUTO_TOKEN + "=false",
        "issuance-portal.token-validation.jwks-min-reload-interval=5m"})
class AccessTokenReloadLimitIT {

    private static final TenantId SANDBOX = new TenantId("sandbox");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private VerifierTokenDecoders decoders;

    @BeforeEach
    void setUp() {
        VerifierFixture.reset();
        decoders.invalidate(SANDBOX);
    }

    @AfterEach
    void tearDown() {
        VerifierFixture.reset();
        decoders.invalidate(SANDBOX);
    }

    @Test
    @DisplayName("B1 — unknownKid_insideTheReloadWindow_returns401AndReadsTheJwksOnce")
    void unknownKid_insideTheReloadWindow_returns401AndReadsTheJwksOnce() throws Exception {
        for (int i = 0; i < 3; i++) {
            String token = AccessTokens.forTenant(SANDBOX, Instant.now())
                    .signedWith(VerifierFixture.newKey("unknown-" + i)).sign();
            mvc.perform(MockMvcRequestBuilders.get("/issuance-portal/api/v1/journey").contextPath("/issuance-portal")
                            .with(request -> {
                                request.setServerName("sandbox.127.0.0.1.nip.io");
                                return request;
                            })
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\""))
                    .andExpect(jsonPath("$.type").value("unauthenticated"));
            // The first token loads the JWKS and reloads it once for its unknown kid; inside the window the
            // following ones do not read the Verifier again.
            VerifierFixture.wire().verify(2, getRequestedFor(urlPathEqualTo(VerifierFixture.JWKS_PATH)));
        }
    }
}
