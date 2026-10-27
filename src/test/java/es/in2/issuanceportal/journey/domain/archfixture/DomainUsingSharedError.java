package es.in2.issuanceportal.journey.domain.archfixture;

import es.in2.issuanceportal.shared.error.ProblemCode;

/** Architecture test fixture: a domain class that leaks the API's error codes into the domain. */
public class DomainUsingSharedError {

    public ProblemCode code() {
        return ProblemCode.JOURNEY_CLOSED;
    }
}
