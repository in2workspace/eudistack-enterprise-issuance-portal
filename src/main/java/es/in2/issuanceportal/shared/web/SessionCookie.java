package es.in2.issuanceportal.shared.web;

import es.in2.issuanceportal.session.api.SessionId;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

import java.util.Arrays;
import java.util.Optional;

/**
 * The {@code journey_session} cookie (design.md AD-9): HttpOnly, Secure, SameSite=Lax so it survives
 * the top-level navigation back from the Verifier, scoped to {@code /issuance-portal} and to the
 * tenant's host only (no Domain).
 */
public final class SessionCookie {

    public static final String NAME = "journey_session";
    static final String PATH = "/issuance-portal";

    private SessionCookie() {
    }

    /** The session the browser sent, if it has the shape of one this service issued. */
    public static Optional<SessionId> read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(cookie -> NAME.equals(cookie.getName()))
                .findFirst()
                .flatMap(cookie -> SessionId.parse(cookie.getValue()));
    }

    public static void write(HttpServletResponse response, SessionId session) {
        response.addHeader(HttpHeaders.SET_COOKIE, base(session.value()).build().toString());
    }

    public static void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, base("").maxAge(0).build().toString());
    }

    private static ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path(PATH);
    }
}
