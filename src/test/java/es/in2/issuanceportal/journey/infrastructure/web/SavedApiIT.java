package es.in2.issuanceportal.journey.infrastructure.web;

import es.in2.issuanceportal.session.api.EntitlementRecord;
import es.in2.issuanceportal.session.api.HolderIdentity;
import es.in2.issuanceportal.session.api.IdentifierType;
import es.in2.issuanceportal.session.api.PersonalIdentifier;
import es.in2.issuanceportal.session.api.Section;
import es.in2.issuanceportal.shared.security.TestHolder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code POST /journey/requests/{request_id}/saved} end to end against PostgreSQL. */
class SavedApiIT extends JourneyRequestsIT {

    private static final String CONFIRMATION = "/journey/confirmation";
    private static final String OFFERS = "/journey/offers";

    private static String saved(String requestId) {
        return "/journey/requests/" + requestId + "/saved";
    }

    private TestHolder confirmedJourney() throws Exception {
        TestHolder holder = journeyWith(requestOf("r1", EntitlementRecord.Status.AVAILABLE, "280000001"),
                requestOf("r2", EntitlementRecord.Status.AVAILABLE, "280000002"));
        mvc.perform(post(holder, CONFIRMATION, reference("r1"))).andExpect(status().isOk());
        return holder;
    }

    @Test
    @DisplayName("AC-05 — postSaved_requestInOffer_is204SavesItDeletesItsOfferAndNeverCallsTheIssuer")
    void postSaved_requestInOffer_is204SavesItDeletesItsOfferAndNeverCallsTheIssuer() throws Exception {
        configure(CONFIG_WITH_ONBOARDING);
        TestHolder holder = confirmedJourney();
        int issuances = issuer.api().calls().size();

        mvc.perform(post(holder, saved("r1"), null))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(jdbc.sql("SELECT saved_at IS NOT NULL FROM \"sandbox_issuance_portal\".journey_issuance "
                + "WHERE journey_id = :id AND request_id = 'r1'").param("id", journeyOf(holder))
                .query(Boolean.class).single()).isTrue();
        assertThat(entryOf(holder, "r1").offerUri()).isEmpty();
        assertThat(entryOf(holder, "r1").onboarding()).isEmpty();
        assertThat(issuer.api().calls()).hasSize(issuances);
        assertThat(issuer.api().renewals()).isEmpty();
        mvc.perform(get(holder, "/journey")).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("completed"))
                .andExpect(jsonPath("$.requests[0].status").value("saved"))
                .andExpect(jsonPath("$.requests[0].offer").doesNotExist())
                .andExpect(jsonPath("$.requests[1].status").value("available"));
    }

    @Test
    @DisplayName("AC-14 — postSaved_savedRequest_refusesConfirmationNewOfferAndAnotherDeclaration")
    void postSaved_savedRequest_refusesConfirmationNewOfferAndAnotherDeclaration() throws Exception {
        TestHolder holder = confirmedJourney();
        mvc.perform(post(holder, saved("r1"), null)).andExpect(status().isNoContent());
        expireAddressOf(holder, "r1");

        for (String path : List.of(CONFIRMATION, OFFERS)) {
            mvc.perform(post(holder, path, reference("r1")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("step_not_allowed"));
        }
        mvc.perform(post(holder, saved("r1"), null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("step_not_allowed"));

        assertThat(issuer.api().calls()).hasSize(1);
        assertThat(issuer.api().renewals()).isEmpty();
        // The other request goes on, with the email verified once.
        mvc.perform(post(holder, CONFIRMATION, reference("r2"))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("NFR-4 — postSaved_everyOutcome_logsNeitherTheOfferNorTheEmailNorTheRequestReference")
    void postSaved_everyOutcome_logsNeitherTheOfferNorTheEmailNorTheRequestReference() throws Exception {
        TestHolder holder = confirmedJourney();
        mvc.perform(post(holder, saved("r1"), null)).andExpect(status().isNoContent());
        mvc.perform(post(holder, saved("r1"), null)).andExpect(status().isConflict());
        expireAddressOf(holder, "r1");
        issuer.api().notRenewable();
        mvc.perform(post(holder, CONFIRMATION, reference("r2"))).andExpect(status().isOk());
        expireAddressOf(holder, "r2");
        mvc.perform(post(holder, OFFERS, reference("r2"))).andExpect(status().isConflict());

        assertThat(logged()).noneMatch(message -> message.contains("openid-credential-offer")
                || message.contains(EMAIL) || message.contains("SIM-r") || message.contains("Elena"));
    }

    @Test
    @DisplayName("AC-14 — postSaved_requestWithoutOfferDelivered_is409StepNotAllowed")
    void postSaved_requestWithoutOfferDelivered_is409StepNotAllowed() throws Exception {
        TestHolder holder = confirmedJourney();

        for (String id : List.of("r2", "r9", "r".repeat(64))) {
            mvc.perform(post(holder, saved(id), null))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("step_not_allowed"));
        }

        assertThat(jdbc.sql("SELECT count(*) FROM \"sandbox_issuance_portal\".journey_issuance WHERE journey_id = :id "
                + "AND saved_at IS NOT NULL").param("id", journeyOf(holder)).query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("ES-01 — postSaved_idOfMoreThanSixtyFourCharacters_is400")
    void postSaved_idOfMoreThanSixtyFourCharacters_is400() throws Exception {
        TestHolder holder = confirmedJourney();

        mvc.perform(post(holder, saved("r".repeat(65)), null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("bad_request"));
    }

    @Test
    @DisplayName("ES-08 — postSaved_endedJourney_is410JourneyClosed")
    void postSaved_endedJourney_is410JourneyClosed() throws Exception {
        TestHolder holder = confirmedJourney();
        jdbc.sql("UPDATE \"sandbox_issuance_portal\".journey SET ended_at = now() WHERE id = :id")
                .param("id", journeyOf(holder)).update();

        mvc.perform(post(holder, saved("r1"), null))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.type").value("journey_closed"));
    }

    @Test
    @DisplayName("AC-08 — postOffers_offerNotRenewable_is409RequestSavedAndTheJourneyGoesOn")
    void postOffers_offerNotRenewable_is409RequestSavedAndTheJourneyGoesOn() throws Exception {
        TestHolder holder = confirmedJourney();
        expireAddressOf(holder, "r1");
        issuer.api().notRenewable();

        mvc.perform(post(holder, OFFERS, reference("r1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("request_saved"));

        assertThat(jdbc.sql("SELECT saved_at IS NOT NULL FROM \"sandbox_issuance_portal\".journey_issuance "
                + "WHERE journey_id = :id AND request_id = 'r1'").param("id", journeyOf(holder))
                .query(Boolean.class).single()).isTrue();
        assertThat(entryOf(holder, "r1").offerUri()).isEmpty();
        assertThat(issuer.api().calls()).hasSize(1);
        mvc.perform(get(holder, "/journey")).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("completed"))
                .andExpect(jsonPath("$.requests[0].status").value("saved"));
        // The same through the confirmation of a request that is already in offer: it is saved too, no second issuance.
        mvc.perform(post(holder, CONFIRMATION, reference("r2"))).andExpect(status().isOk());
        expireAddressOf(holder, "r2");
        mvc.perform(post(holder, CONFIRMATION, reference("r2")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("request_saved"));
        assertThat(issuer.api().calls()).hasSize(2);
    }

    @Test
    @DisplayName("AC-14 — postSaved_declaredWithoutSaving_aNewJourneyGetsTheRequestAgainAvailable")
    void postSaved_declaredWithoutSaving_aNewJourneyGetsTheRequestAgainAvailable() throws Exception {
        String config = new ClassPathResource("authenticsource/doctorid-portal-config.json")
                .getContentAsString(StandardCharsets.UTF_8);
        configure(config);
        TestHolder holder = TestHolder.of(SANDBOX, holderRefs);
        UUID first = openJourneyWithIdentity(holder);
        mvc.perform(post(holder, "/entitlement", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.requests[0].status").value("available"));
        sessions.write(SANDBOX, first, Section.EMAIL, new es.in2.issuanceportal.session.api.EmailRecord(EMAIL).toJson());
        sessions.write(SANDBOX, first, Section.EMAIL_VERIFICATION, new es.in2.issuanceportal.session.api
                .EmailVerificationRecord(EMAIL, null, null, 0, 1, clock.instant()).toJson());
        mvc.perform(post(holder, CONFIRMATION, reference("r1"))).andExpect(status().isOk());
        mvc.perform(post(holder, saved("r1"), null)).andExpect(status().isNoContent());
        mvc.perform(post(holder, "/journey/withdrawal", null)).andExpect(status().isOk());

        UUID second = openJourneyWithIdentity(holder);

        assertThat(second).isNotEqualTo(first);
        mvc.perform(post(holder, "/entitlement", null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("confirmed"))
                .andExpect(jsonPath("$.requests[0].status").value("available"));
        mvc.perform(get(holder, "/journey")).andExpect(jsonPath("$.requests[0].status").value("available"));
    }

    /** POST /journey with the holder's token, then the identity of the simulated person 00000001R. */
    private UUID openJourneyWithIdentity(TestHolder holder) throws Exception {
        mvc.perform(post(holder, "/journey", null).accept(MediaType.APPLICATION_JSON)).andExpect(status().is2xxSuccessful());
        UUID id = journeyOf(holder);
        sessions.write(SANDBOX, id, Section.IDENTITY, new HolderIdentity("Lucía", "Prueba Activa",
                new PersonalIdentifier(IdentifierType.PERSONAL_ID, "00000001R"), null).toJson());
        return id;
    }
}
