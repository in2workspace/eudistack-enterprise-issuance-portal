package es.in2.issuanceportal.shared.security;

import es.in2.issuanceportal.shared.error.ProblemCode;
import es.in2.issuanceportal.shared.error.Problems;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Answers every failed authentication as a Problem (RFC 9457) with the challenge of RFC 6750: no token is
 * {@code Bearer}, a token that is not valid is {@code Bearer error="invalid_token"}. When the token could not be
 * judged because a dependency is down, the Problem is that dependency's 503 and there is no challenge.
 */
@Component
class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        if (exception instanceof TokenUnavailableException unavailable) {
            Problems.write(response, unavailable.problem());
            return;
        }
        boolean noToken = exception instanceof InsufficientAuthenticationException;
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, noToken ? "Bearer" : "Bearer error=\"invalid_token\"");
        Problems.write(response, ProblemCode.UNAUTHENTICATED);
    }
}
