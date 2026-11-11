package es.in2.issuanceportal.journey.infrastructure.web;

import es.in2.issuanceportal.session.api.SessionId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * NFR-1: the confirmation of a tenant with onboarding, with the Verifier, the Issuer and the Wallet Unit Service
 * simulated without latency, takes less than 300 ms at p95. It is measured at the HTTP boundary of the service
 * (the request goes through the whole filter chain and controller), with 200 confirmations of fresh journeys
 * after a warm-up. Tagged so a slow runner can leave it out with {@code excludeTags 'performance'}.
 */
@Tag("performance")
class ConfirmationPerformanceIT extends SystemIssuanceITSupport {

    private static final int WARM_UP = 30;
    private static final int MEASURED = 200;
    private static final long P95_LIMIT_MILLIS = 300;

    @Test
    @DisplayName("EUD-277 NFR-1 — 200 confirmations without latency in the systems: p95 under 300 ms")
    void confirm_systemsWithoutLatency_p95IsUnder300Millis() throws Exception {
        configure(true);
        verifierGrants();
        issuerIssues();
        WIRE.stubFor(post(urlEqualTo(TICKETS_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"onboardingUrl\":\"https://sandbox.wallet.test/t/{{randomValue type='UUID'}}\",\"expiresIn\":540}")));
        for (int i = 0; i < WARM_UP; i++) {
            SessionId session = entitledJourney();
            mvc.perform(confirmation(session)).andExpect(status().isOk());
        }

        List<Long> nanos = new ArrayList<>();
        for (int i = 0; i < MEASURED; i++) {
            SessionId session = entitledJourney();
            long start = System.nanoTime();
            mvc.perform(confirmation(session)).andExpect(status().isOk());
            nanos.add(System.nanoTime() - start);
        }

        nanos.sort(Comparator.naturalOrder());
        double p50 = nanos.get(MEASURED / 2 - 1) / 1_000_000.0;
        double p95 = nanos.get((int) Math.ceil(MEASURED * 0.95) - 1) / 1_000_000.0;
        double max = nanos.getLast() / 1_000_000.0;
        System.out.printf("NFR-1 confirmation over %d requests: p50 = %.1f ms, p95 = %.1f ms, max = %.1f ms%n",
                MEASURED, p50, p95, max);
        assertThat(p95).as("p95 of the confirmation in ms").isLessThan(P95_LIMIT_MILLIS);
    }
}
