package es.in2.issuanceportal.shared.error;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

import java.io.IOException;
import java.net.URI;

/** Builds Problems in the shape of the contract, for the exception handler and for filters. */
public final class Problems {

    private Problems() {
    }

    public static ProblemDetail of(ProblemCode problem) {
        ProblemDetail detail = ProblemDetail.forStatus(problem.status());
        detail.setType(URI.create(problem.code()));
        detail.setTitle(problem.title());
        return detail;
    }

    /** For filters, which answer before Spring MVC and its message converters are involved. */
    public static void write(HttpServletResponse response, ProblemCode problem) throws IOException {
        response.setStatus(problem.status());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("{\"type\":\"%s\",\"title\":\"%s\",\"status\":%d}"
                .formatted(problem.code(), problem.title(), problem.status()));
    }
}
