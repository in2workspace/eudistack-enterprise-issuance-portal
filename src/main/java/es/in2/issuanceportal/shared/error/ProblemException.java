package es.in2.issuanceportal.shared.error;

import java.time.Duration;
import java.util.Optional;

/**
 * A failure that reaches the interface as a Problem. Plain Java, so any layer can throw it without
 * depending on Spring.
 */
public class ProblemException extends RuntimeException {

    private final transient ProblemCode problem;
    private final transient Duration retryAfter;

    public ProblemException(ProblemCode problem) {
        super(problem.code());
        this.problem = problem;
        this.retryAfter = null;
    }

    public ProblemException(ProblemCode problem, Throwable cause) {
        super(problem.code(), cause);
        this.problem = problem;
        this.retryAfter = null;
    }

    /** A failure the client may retry after the given time, sent as {@code Retry-After}. */
    public ProblemException(ProblemCode problem, Duration retryAfter) {
        super(problem.code());
        this.problem = problem;
        this.retryAfter = retryAfter;
    }

    public ProblemCode problem() {
        return problem;
    }

    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
