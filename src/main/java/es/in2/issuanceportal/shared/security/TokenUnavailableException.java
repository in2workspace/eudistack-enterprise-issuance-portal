package es.in2.issuanceportal.shared.security;

import es.in2.issuanceportal.shared.error.ProblemCode;
import org.springframework.security.core.AuthenticationException;

/**
 * The token could not be judged because something the service depends on is missing: the Verifier's keys or the
 * tenant's configuration. The caller gets that Problem (503), not a 401: the token may be fine. It is not an
 * {@code AuthenticationServiceException}, which the resource server filter would rethrow as a 500.
 */
final class TokenUnavailableException extends AuthenticationException {

    private final transient ProblemCode problem;

    TokenUnavailableException(ProblemCode problem) {
        super(problem.code());
        this.problem = problem;
    }

    ProblemCode problem() {
        return problem;
    }
}
