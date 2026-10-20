package es.in2.issuanceportal.shared.error;

/**
 * A failure that reaches the interface as a Problem. Plain Java, so any layer can throw it without
 * depending on Spring.
 */
public class ProblemException extends RuntimeException {

    private final transient ProblemCode problem;

    public ProblemException(ProblemCode problem) {
        super(problem.code());
        this.problem = problem;
    }

    public ProblemException(ProblemCode problem, Throwable cause) {
        super(problem.code(), cause);
        this.problem = problem;
    }

    public ProblemCode problem() {
        return problem;
    }
}
