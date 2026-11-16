package es.in2.issuanceportal.authenticsource.domain;

import java.util.List;

/**
 * What a search of the authentic source finds (design.md §3.1): nobody, the holder without requests, or their
 * requests in the order the source gave them.
 */
public sealed interface SourceRequests {

    static SourceRequests notFound() {
        return new NotFound();
    }

    static SourceRequests noRequests() {
        return new NoRequests();
    }

    static SourceRequests of(SourceRequest... requests) {
        return new Requests(List.of(requests));
    }

    /** The holder is not in the source. */
    record NotFound() implements SourceRequests {
    }

    /** The holder is in the source without any request. */
    record NoRequests() implements SourceRequests {
    }

    /** A source never says {@code under_review}: that status is the journey's. */
    record Requests(List<SourceRequest> requests) implements SourceRequests {

        public Requests {
            requests = List.copyOf(requests);
            if (requests.stream().anyMatch(request -> request.status() == RequestStatus.UNDER_REVIEW)) {
                throw new IllegalArgumentException("status");
            }
        }
    }
}
