package es.in2.issuanceportal.journey.infrastructure.web;

import es.in2.issuanceportal.journey.application.port.IssuanceRejectedException;
import es.in2.issuanceportal.journey.application.port.IssuanceUnavailableException;
import es.in2.issuanceportal.journey.application.port.OnboardingTicketException;
import es.in2.issuanceportal.session.api.EntitlementRecord;
import es.in2.issuanceportal.shared.security.TestHolder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code POST /journey/offers} end to end against PostgreSQL, with the Issuer in the hands of the test. */
class OfferApiIT extends JourneyRequestsIT {

    private static final String OFFERS = "/journey/offers";
    private static final String CONFIRMATION = "/journey/confirmation";

    private TestHolder confirmedJourney() throws Exception {
        TestHolder holder = journeyWith(requestOf("r1", EntitlementRecord.Status.AVAILABLE, "280000001"),
                requestOf("r2", EntitlementRecord.Status.AVAILABLE, "280000002"));
        mvc.perform(post(holder, CONFIRMATION, reference("r1"))).andExpect(status().isOk());
        return holder;
    }

    @Test
    @DisplayName("AC-07 — postOffers_addressExpired_isANewOfferOfTheSameProcessWithoutANewIssuance")
    void postOffers_addressExpired_isANewOfferOfTheSameProcessWithoutANewIssuance() throws Exception {
        TestHolder holder = confirmedJourney();
        UUID process = jdbc.sql("SELECT issuance_process_id FROM \"sandbox_issuance_portal\".journey_issuance "
                + "WHERE journey_id = :id").param("id", journeyOf(holder)).query(UUID.class).single();
        UUID key = entryOf(holder, "r1").idempotencyKey();
        expireAddressOf(holder, "r1");

        mvc.perform(post(holder, OFFERS, reference("r1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credential_offer_uri").value(org.hamcrest.Matchers.containsString("renewed")))
                .andExpect(jsonPath("$.expires_at").isNotEmpty());

        assertThat(issuer.api().calls()).hasSize(1);
        assertThat(issuer.api().renewals()).containsExactly(process);
        assertThat(issuer.api().processes()).isEqualTo(1);
        assertThat(entryOf(holder, "r1").idempotencyKey()).isEqualTo(key);
        assertThat(entryOf(holder, "r1").offerUri().orElseThrow()).contains("renewed");
        assertThat(jdbc.sql("SELECT count(*) FROM \"sandbox_issuance_portal\".journey_issuance WHERE journey_id = :id")
                .param("id", journeyOf(holder)).query(Long.class).single()).isEqualTo(1L);
        mvc.perform(get(holder, "/journey")).andExpect(status().isOk())
                .andExpect(jsonPath("$.requests[0].status").value("in_offer"))
                .andExpect(jsonPath("$.requests[0].offer.credential_offer_uri")
                        .value(org.hamcrest.Matchers.containsString("renewed")));
    }

    @Test
    @DisplayName("EUD-359 AC-06 — postOffers_requestWithProcessUnderReview_is409StepNotAllowedAndNothingIsAsked")
    void postOffers_requestWithProcessUnderReview_is409StepNotAllowedAndNothingIsAsked() throws Exception {
        TestHolder holder = confirmedJourney();
        mvc.perform(post(holder, "/entitlement/requests/r1/dispute", null)).andExpect(status().isNoContent());
        expireAddressOf(holder, "r1");

        for (String path : List.of(OFFERS, CONFIRMATION)) {
            mvc.perform(post(holder, path, reference("r1")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("step_not_allowed"));
        }

        assertThat(issuer.api().renewals()).isEmpty();
        assertThat(issuer.api().calls()).hasSize(1);
    }

    @Test
    @DisplayName("EUD-359 AC-06 — postDispute_requestAlreadySaved_is409RequestNotAvailableAndTheSourceIsNotTold")
    void postDispute_requestAlreadySaved_is409RequestNotAvailableAndTheSourceIsNotTold() throws Exception {
        TestHolder holder = confirmedJourney();
        mvc.perform(post(holder, "/journey/requests/r1/saved", null)).andExpect(status().isNoContent());

        mvc.perform(post(holder, "/entitlement/requests/r1/dispute", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("request_not_available"));

        assertThat(logged()).noneMatch(line -> line.contains("entitlement dispute"));
        mvc.perform(post(holder, "/entitlement/requests/r2/dispute", null)).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("ES-06 — postOffers_addressStillWorking_is409StepNotAllowedAndTheOfferKeepsServing")
    void postOffers_addressStillWorking_is409StepNotAllowedAndTheOfferKeepsServing() throws Exception {
        TestHolder holder = confirmedJourney();
        String offer = entryOf(holder, "r1").offerUri().orElseThrow();

        mvc.perform(post(holder, OFFERS, reference("r1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("step_not_allowed"));

        assertThat(issuer.api().renewals()).isEmpty();
        assertThat(entryOf(holder, "r1").offerUri()).contains(offer);
        mvc.perform(get(holder, "/journey")).andExpect(jsonPath("$.requests[0].offer.credential_offer_uri").value(offer));
    }

    @Test
    @DisplayName("ES-06 — postOffers_requestWithoutProcessOrUnknown_is409StepNotAllowed")
    void postOffers_requestWithoutProcessOrUnknown_is409StepNotAllowed() throws Exception {
        TestHolder holder = confirmedJourney();
        expireAddressOf(holder, "r1");

        for (String id : List.of("r2", "r9", "r".repeat(64))) {
            mvc.perform(post(holder, OFFERS, reference(id)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("step_not_allowed"));
        }

        assertThat(issuer.api().renewals()).isEmpty();
    }

    @Test
    @DisplayName("ES-01 — postOffers_withoutRequestIdOrWithAnInvalidOne_is400AndNothingIsAsked")
    void postOffers_withoutRequestIdOrWithAnInvalidOne_is400AndNothingIsAsked() throws Exception {
        TestHolder holder = confirmedJourney();
        expireAddressOf(holder, "r1");

        for (String body : List.of("{}", "{\"request_id\":null}", "{\"request_id\":\"\"}", "{\"request_id\":\"  \"}",
                "{\"request_id\":\"" + "r".repeat(65) + "\"}", "{\"request_id\":12}", "[]", "not json")) {
            mvc.perform(post(holder, OFFERS, body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("bad_request"));
        }

        assertThat(issuer.api().renewals()).isEmpty();
    }

    @Test
    @DisplayName("ES-07 — postOffers_ticketFails_is503OnboardingUnavailableWithoutOfferAndTheJourneyStaysCompleted")
    void postOffers_ticketFails_is503OnboardingUnavailableWithoutOfferAndTheJourneyStaysCompleted() throws Exception {
        configure(CONFIG_WITH_ONBOARDING);
        TestHolder holder = confirmedJourney();
        expireAddressOf(holder, "r1");
        tickets.failWith(OnboardingTicketException.Reason.UNAVAILABLE);

        mvc.perform(post(holder, OFFERS, reference("r1")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("onboarding_unavailable"));

        assertThat(jdbc.sql("SELECT state FROM \"sandbox_issuance_portal\".journey WHERE id = :id")
                .param("id", journeyOf(holder)).query(String.class).single()).isEqualTo("completed");
        mvc.perform(get(holder, "/journey")).andExpect(status().isOk())
                .andExpect(jsonPath("$.requests[0].status").value("in_offer"))
                .andExpect(jsonPath("$.requests[0].offer").doesNotExist());
        tickets.recover();

        mvc.perform(post(holder, OFFERS, reference("r1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboarding_url").exists());
        assertThat(issuer.api().calls()).hasSize(1);
        assertThat(issuer.api().renewals()).hasSize(2);
    }

    @Test
    @DisplayName("ES-07 — postOffers_issuerRejectsOrIsUnavailable_is502Or503")
    void postOffers_issuerRejectsOrIsUnavailable_is502Or503() throws Exception {
        TestHolder holder = confirmedJourney();
        expireAddressOf(holder, "r1");

        issuer.api().failRenewalWith(new IssuanceRejectedException(IssuanceRejectedException.Reason.REQUEST_REJECTED));
        mvc.perform(post(holder, OFFERS, reference("r1")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value("issuance_rejected"));
        issuer.api().failRenewalWith(new IssuanceUnavailableException(IssuanceUnavailableException.Reason.NETWORK));
        mvc.perform(post(holder, OFFERS, reference("r1")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("issuance_unavailable"));
    }

    @Test
    @DisplayName("ES-08 — postOffers_endedJourney_is410JourneyClosed")
    void postOffers_endedJourney_is410JourneyClosed() throws Exception {
        TestHolder holder = confirmedJourney();
        expireAddressOf(holder, "r1");
        jdbc.sql("UPDATE \"sandbox_issuance_portal\".journey SET ended_at = now() WHERE id = :id")
                .param("id", journeyOf(holder)).update();

        mvc.perform(post(holder, OFFERS, reference("r1")))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.type").value("journey_closed"));
    }

    @Test
    @DisplayName("NFR-4 — postOffers_logsNeitherTheOfferNorTheEmailNorTheRequestReference")
    void postOffers_logsNeitherTheOfferNorTheEmailNorTheRequestReference() throws Exception {
        TestHolder holder = confirmedJourney();
        expireAddressOf(holder, "r1");

        mvc.perform(post(holder, OFFERS, reference("r1"))).andExpect(status().isOk());

        assertThat(logged()).noneMatch(message -> message.contains("openid-credential-offer")
                || message.contains(EMAIL) || message.contains("SIM-r1") || message.contains("Elena"));
    }
}
