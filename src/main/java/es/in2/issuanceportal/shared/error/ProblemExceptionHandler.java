package es.in2.issuanceportal.shared.error;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Duration;
import java.util.stream.Collectors;

/**
 * Turns every failure into a Problem (RFC 9457). An invalid request body is {@code bad_request}; other
 * framework errors keep Spring's own Problems; anything unexpected is a 500 without internal detail.
 */
@Slf4j
@RestControllerAdvice
public class ProblemExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ProblemException.class)
    ResponseEntity<ProblemDetail> handleProblem(ProblemException exception) {
        if (exception.problem() == ProblemCode.SERVICE_UNAVAILABLE) {
            log.error("Dependency unavailable", exception);
        }
        ProblemDetail detail = Problems.of(exception.problem());
        return exception.retryAfter()
                .map(wait -> ResponseEntity.status(detail.getStatus())
                        .header(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds(wait)))
                        .body(detail))
                .orElseGet(() -> respond(detail));
    }

    /** Whole seconds, rounded up so a client that waits exactly that long is accepted. */
    private static long retryAfterSeconds(Duration wait) {
        return Math.max(1, wait.plusMillis(999).toSeconds());
    }

    /**
     * With the database down, a transactional service fails when its transaction opens
     * ({@link CannotCreateTransactionException}), or, if the database goes away mid-transaction, when
     * the rollback itself cannot reach it ({@link TransactionSystemException}).
     */
    @ExceptionHandler({CannotGetJdbcConnectionException.class, DataAccessResourceFailureException.class,
            TransientDataAccessException.class, CannotCreateTransactionException.class,
            TransactionSystemException.class})
    ResponseEntity<ProblemDetail> handleDatabaseUnavailable(Exception exception) {
        log.error("Database unavailable", exception);
        return respond(Problems.of(ProblemCode.SERVICE_UNAVAILABLE));
    }

    /** Names the invalid fields, never their values: a request body may carry personal data. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail detail = Problems.of(ProblemCode.BAD_REQUEST);
        detail.setDetail("Invalid fields: " + exception.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getField)
                .distinct()
                .sorted()
                .collect(Collectors.joining(", ")));
        return ResponseEntity.status(detail.getStatus()).body(detail);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail detail = Problems.of(ProblemCode.BAD_REQUEST);
        return ResponseEntity.status(detail.getStatus()).body(detail);
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
