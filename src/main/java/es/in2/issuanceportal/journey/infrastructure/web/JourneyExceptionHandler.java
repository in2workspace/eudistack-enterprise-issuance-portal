package es.in2.issuanceportal.journey.infrastructure.web;

import es.in2.issuanceportal.journey.domain.JourneyClosedException;
import es.in2.issuanceportal.shared.error.ProblemCode;
import es.in2.issuanceportal.shared.error.Problems;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates the journey's domain exception into the contract's Problem, for any module whose request
 * goes through the journey ports. Ordered first so the catch-all of ProblemExceptionHandler never
 * answers it as a 500.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class JourneyExceptionHandler {

    @ExceptionHandler(JourneyClosedException.class)
    ResponseEntity<ProblemDetail> handleJourneyClosed(JourneyClosedException exception) {
        ProblemDetail detail = Problems.of(ProblemCode.JOURNEY_CLOSED);
        return ResponseEntity.status(detail.getStatus()).body(detail);
    }
}
