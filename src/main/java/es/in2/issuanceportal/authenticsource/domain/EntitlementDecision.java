package es.in2.issuanceportal.authenticsource.domain;

import java.util.List;
import java.util.Optional;

/**
 * The outcome of an answer of the source (design.md §3.3). The requests carry the status they have once
 * the ones the holder already put under review are applied.
 */
public sealed interface EntitlementDecision {

    List<SourceRequest> requests();

    /** At least one request is available. */
    record Confirmed(List<SourceRequest> requests) implements EntitlementDecision {
        public Confirmed {
            requests = List.copyOf(requests);
        }
    }

    /** No request is available. {@code reason} is empty when there are requests, each saying why. */
    record NotConfirmed(Optional<NotConfirmedReason> reason, List<SourceRequest> requests)
            implements EntitlementDecision {
        public NotConfirmed {
            requests = List.copyOf(requests);
        }
    }
}
