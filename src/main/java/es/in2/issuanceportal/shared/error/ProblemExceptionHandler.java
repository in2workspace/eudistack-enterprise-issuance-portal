package es.in2.issuanceportal.shared.error;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every failure into a Problem (RFC 9457). Framework errors keep Spring's own Problems; anything
 * unexpected is a 500 without internal detail.
 */
@Slf4j
@RestControllerAdvice
public class ProblemExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ProblemException.class)
    ResponseEntity<ProblemDetail> handleProblem(ProblemException exception) {
        if (exception.problem() == ProblemCode.SERVICE_UNAVAILABLE) {
            log.error("Dependency unavailable", exception);
        }
        return respond(Problems.of(exception.problem()));
    }

    /**
     * With the database down, a transactional service fails when its transaction opens
     * ({@link CannotCreateTransactionException}), before any repository call.
     */
    @ExceptionHandler({CannotGetJdbcConnectionException.class, DataAccessResourceFailureException.class,
            TransientDataAccessException.class, CannotCreateTransactionException.class})
    ResponseEntity<ProblemDetail> handleDatabaseUnavailable(Exception exception) {
        log.error("Database unavailable", exception);
        return respond(Problems.of(ProblemCode.SERVICE_UNAVAILABLE));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception exception) {
        log.error("Unexpected error", exception);
        ProblemDetail detail = ProblemDetail.forStatus(500);
        detail.setTitle("Internal error");
        return respond(detail);
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail detail) {
        return ResponseEntity.status(detail.getStatus()).body(detail);
    }
}
