package es.in2.issuanceportal.authenticsource.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The table of design.md §3.3 for an answer of the source. */
class EntitlementRulesTest {

    private static SourceRequest request(String ref, RequestStatus status) {
        return new SourceRequest(ref, status, List.of(), false, null, null, null, null,
                Map.of("registration_number", "280000001"));
    }

    @Test
    void decide_notFound_isNotConfirmedNotFoundWithoutRequests() {
        assertThat(EntitlementRules.decide(SourceRequests.notFound(), Set.of()))
                .isEqualTo(new EntitlementDecision.NotConfirmed(Optional.of(NotConfirmedReason.NOT_FOUND), List.of()));
    }

    @Test
    void decide_noRequests_isNotConfirmedNoRequests() {
        assertThat(EntitlementRules.decide(SourceRequests.noRequests(), Set.of()))
                .isEqualTo(new EntitlementDecision.NotConfirmed(Optional.of(NotConfirmedReason.NO_REQUESTS), List.of()));
    }

    @Test
    void decide_emptyListOfRequests_isNoRequests() {
        assertThat(EntitlementRules.decide(new SourceRequests.Requests(List.of()), Set.of()))
                .isEqualTo(new EntitlementDecision.NotConfirmed(Optional.of(NotConfirmedReason.NO_REQUESTS), List.of()));
    }

    @Test
    @DisplayName("EC-08 — decide_oneAvailableAndOneMismatch_isConfirmedWithBothInTheSourceOrder")
    void decide_oneAvailableAndOneMismatch_isConfirmedWithBothInTheSourceOrder() {
        SourceRequest mismatch = request("B", RequestStatus.IDENTITY_MISMATCH);
        SourceRequest available = request("A", RequestStatus.AVAILABLE);

        EntitlementDecision decision = EntitlementRules.decide(SourceRequests.of(mismatch, available), Set.of());

        assertThat(decision).isEqualTo(new EntitlementDecision.Confirmed(List.of(mismatch, available)));
    }

    @Test
    @DisplayName("AC-04 — decide_noRequestAvailable_isNotConfirmedWithoutReasonAndKeepsTheRequests")
    void decide_noRequestAvailable_isNotConfirmedWithoutReasonAndKeepsTheRequests() {
        SourceRequest identity = request("A", RequestStatus.IDENTITY_MISMATCH);
        SourceRequest identifier = request("B", RequestStatus.IDENTIFIER_MISMATCH);

        assertThat(EntitlementRules.decide(SourceRequests.of(identity, identifier), Set.of()))
                .isEqualTo(new EntitlementDecision.NotConfirmed(Optional.empty(), List.of(identity, identifier)));
    }

    @Test
    @DisplayName("EC-02 — decide_requestAlreadyUnderReview_staysUnderReviewEvenIfTheSourceGivesItAvailable")
    void decide_requestAlreadyUnderReview_staysUnderReviewEvenIfTheSourceGivesItAvailable() {
        SourceRequest first = request("A", RequestStatus.AVAILABLE);
        SourceRequest second = request("B", RequestStatus.AVAILABLE);

        EntitlementDecision decision = EntitlementRules.decide(SourceRequests.of(first, second), Set.of("A"));

        assertThat(decision).isInstanceOf(EntitlementDecision.Confirmed.class);
        assertThat(decision.requests()).extracting(SourceRequest::status)
                .containsExactly(RequestStatus.UNDER_REVIEW, RequestStatus.AVAILABLE);
    }

    @Test
    void decide_onlyRequestUnderReview_isNotConfirmedWithoutReason() {
        EntitlementDecision decision = EntitlementRules.decide(
                SourceRequests.of(request("A", RequestStatus.AVAILABLE)), Set.of("A"));

        assertThat(decision).isInstanceOf(EntitlementDecision.NotConfirmed.class);
        assertThat(((EntitlementDecision.NotConfirmed) decision).reason()).isEmpty();
        assertThat(decision.requests()).extracting(SourceRequest::status).containsExactly(RequestStatus.UNDER_REVIEW);
    }

    @Test
    void decide_underReviewOfAnotherReference_changesNothing() {
        SourceRequest available = request("A", RequestStatus.AVAILABLE);

        assertThat(EntitlementRules.decide(SourceRequests.of(available), Set.of("Z")))
                .isEqualTo(new EntitlementDecision.Confirmed(List.of(available)));
    }

    @Test
    void decide_mismatchAlreadyReportedByTheSource_isNeverMadeUnderReview() {
        SourceRequest mismatch = request("A", RequestStatus.IDENTITY_MISMATCH);

        assertThat(EntitlementRules.decide(SourceRequests.of(mismatch), Set.of("A")).requests())
                .containsExactly(mismatch);
    }
}
