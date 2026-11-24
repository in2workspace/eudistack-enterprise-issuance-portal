package es.in2.issuanceportal.journey.infrastructure.web;

import es.in2.issuanceportal.LatencySample;
import es.in2.issuanceportal.session.api.EntitlementRecord;
import es.in2.issuanceportal.shared.security.TestHolder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * EUD-359 NFR-2: the new offer and the declaration of saved, with the Issuer simulated without latency, take less
 * than 300 ms at p95. Measured at the HTTP boundary of the service like the confirmation (ConfirmationPerformanceIT),
 * each call on a fresh journey with its request confirmed, after a warm-up. Tagged so a slow runner can leave it out
 * with {@code excludeTags 'performance'}.
 */
@Tag("performance")
class RequestsPerformanceIT extends JourneyRequestsIT {

    private static final int WARM_UP = 30;
    private static final int MEASURED = 200;
    private static final long P95_LIMIT_MILLIS = 300;

    private TestHolder confirmedJourney() throws Exception {
        TestHolder holder = journeyWith(requestOf("r1", EntitlementRecord.Status.AVAILABLE, "280000001"));
        mvc.perform(post(holder, "/journey/confirmation", reference("r1"))).andExpect(status().isOk());
        return holder;
    }

    @Test
    @DisplayName("EUD-359 NFR-2 — 200 new offers without latency in the Issuer: p95 under 300 ms")
    void newOffer_issuerWithoutLatency_p95IsUnder300Millis() throws Exception {
        LatencySample sample = new LatencySample("new offer");
        for (int i = 0; i < WARM_UP + MEASURED; i++) {
            TestHolder holder = confirmedJourney();
            expireAddressOf(holder, "r1");
            if (i < WARM_UP) {
                mvc.perform(post(holder, "/journey/offers", reference("r1"))).andExpect(status().isOk());
            } else {
                sample.time(() -> mvc.perform(post(holder, "/journey/offers", reference("r1")))
                        .andExpect(status().isOk()));
            }
        }

        assertThat(sample.p95Millis()).as("p95 of the new offer in ms").isLessThan(P95_LIMIT_MILLIS);
    }

    @Test
    @DisplayName("EUD-359 NFR-2 — 200 declarations of saved: p95 under 300 ms")
    void declareSaved_p95IsUnder300Millis() throws Exception {
        LatencySample sample = new LatencySample("declaration of saved");
        for (int i = 0; i < WARM_UP + MEASURED; i++) {
            TestHolder holder = confirmedJourney();
            if (i < WARM_UP) {
                mvc.perform(post(holder, "/journey/requests/r1/saved", null)).andExpect(status().isNoContent());
            } else {
                sample.time(() -> mvc.perform(post(holder, "/journey/requests/r1/saved", null))
                        .andExpect(status().isNoContent()));
            }
        }

        assertThat(sample.p95Millis()).as("p95 of the declaration of saved in ms").isLessThan(P95_LIMIT_MILLIS);
    }
}
