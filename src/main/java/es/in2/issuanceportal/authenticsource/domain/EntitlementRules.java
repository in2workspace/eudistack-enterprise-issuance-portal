package es.in2.issuanceportal.authenticsource.domain;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The rules of design.md §3.3 for an answer of the source, the same for every adapter. A source that
 * does not answer never reaches them: that is an unavailable source, not a decision. The module never
 * compares the holder's identity with the source's data (AD-5): it uses the status the source gives.
 */
public final class EntitlementRules {

    private EntitlementRules() {
    }

    /**
     * @param underReview the source references of the requests the holder already put under review in this
     *                    journey: they stay under review even if the source gives them as available
     */
    public static EntitlementDecision decide(SourceRequests answer, Set<String> underReview) {
        return switch (answer) {
            case SourceRequests.NotFound _ -> notConfirmed(NotConfirmedReason.NOT_FOUND);
            case SourceRequests.NoRequests _ -> notConfirmed(NotConfirmedReason.NO_REQUESTS);
            case SourceRequests.Requests(List<SourceRequest> requests) when requests.isEmpty() ->
                    notConfirmed(NotConfirmedReason.NO_REQUESTS);
            case SourceRequests.Requests(List<SourceRequest> requests) -> decideOn(apply(requests, underReview));
        };
    }

    private static EntitlementDecision decideOn(List<SourceRequest> requests) {
        boolean anyAvailable = requests.stream().anyMatch(request -> request.status() == RequestStatus.AVAILABLE);
        return anyAvailable
                ? new EntitlementDecision.Confirmed(requests)
                : new EntitlementDecision.NotConfirmed(Optional.empty(), requests);
    }

    private static List<SourceRequest> apply(List<SourceRequest> requests, Set<String> underReview) {
        return requests.stream()
                .map(request -> request.status() == RequestStatus.AVAILABLE && underReview.contains(request.sourceRef())
                        ? request.withStatus(RequestStatus.UNDER_REVIEW)
                        : request)
                .toList();
    }

    private static EntitlementDecision notConfirmed(NotConfirmedReason reason) {
        return new EntitlementDecision.NotConfirmed(Optional.of(reason), List.of());
    }
}
