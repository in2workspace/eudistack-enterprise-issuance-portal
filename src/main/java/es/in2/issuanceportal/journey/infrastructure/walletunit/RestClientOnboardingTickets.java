package es.in2.issuanceportal.journey.infrastructure.walletunit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import es.in2.issuanceportal.journey.application.port.OnboardingTicket;
import es.in2.issuanceportal.journey.application.port.OnboardingTicketException;
import es.in2.issuanceportal.journey.application.port.OnboardingTicketException.Reason;
import es.in2.issuanceportal.journey.application.port.OnboardingTickets;
import es.in2.issuanceportal.journey.application.port.SystemToken;
import es.in2.issuanceportal.journey.application.port.SystemTokenProvider;
import es.in2.issuanceportal.journey.application.port.SystemTokenUnavailableException;
import es.in2.issuanceportal.shared.tenant.TenantId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;

/**
 * {@code POST /onboarding-tickets} of the tenant's Wallet Unit Service (EUD-8 annex {@code openapi.yaml},
 * {@code createOnboardingTicket}; design.md §3.4).
 *
 * <p>The call carries the system token of the tenant, never the holder's (NFR-4). It adds no tenant or forwarded
 * headers: the configured address is the tenant's canonical wallet host, which already says which tenant it is.
 * There is one attempt, bounded by the configured wait. A 401 invalidates the cached token. Nothing of the
 * request, the ticket or the token is logged, and no cause is kept: a failure only becomes a reason.
 */
@Slf4j
final class RestClientOnboardingTickets implements OnboardingTickets {

    private static final String PATH = "/onboarding-tickets";

    private final RestClient http;
    private final SystemTokenProvider systemTokens;
    private final Clock clock;

    RestClientOnboardingTickets(RestClient http, SystemTokenProvider systemTokens, Clock clock) {
        this.http = http;
        this.systemTokens = systemTokens;
        this.clock = clock;
    }

    @Override
    public OnboardingTicket request(TenantId tenant, String walletUnitServiceUrl, String email,
                                    String credentialOffer, Duration expiresIn) {
        SystemToken token;
        try {
            token = systemTokens.tokenFor(tenant);
        } catch (SystemTokenUnavailableException e) {
            log.warn("No system token of tenant {} to request an onboarding ticket: {}", tenant, e.reason());
            throw new OnboardingTicketException(Reason.SYSTEM_TOKEN_UNAVAILABLE);
        }
        // The ticket lasts from when the service created it, which is not before the request left.
        Instant sentAt = clock.instant();
        Answer answer;
        try {
            answer = http.post()
                    .uri(stripTrailingSlash(walletUnitServiceUrl) + PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header(AUTHORIZATION, "Bearer " + token.value())
                    .body(new RequestBody(email, credentialOffer, expiresIn.toSeconds()))
                    .exchange((req, res) -> new Answer(res.getStatusCode(),
                            res.getStatusCode().is2xxSuccessful() ? res.bodyTo(ResponseBody.class) : null));
        } catch (ResourceAccessException e) {
            log.warn("The Wallet Unit Service of tenant {} did not answer: {}", tenant,
                    e.getCause() == null ? "I/O error" : e.getCause().getClass().getSimpleName());
            throw new OnboardingTicketException(Reason.UNAVAILABLE);
        } catch (RestClientException e) {
            log.warn("The Wallet Unit Service of tenant {} sent a response that cannot be read", tenant);
            throw new OnboardingTicketException(Reason.UNEXPECTED_RESPONSE);
        }
        return ticketOf(tenant, answer, sentAt);
    }

    private OnboardingTicket ticketOf(TenantId tenant, Answer answer, Instant sentAt) {
        int status = answer.status.value();
        if (!answer.status.is2xxSuccessful()) {
            log.warn("The Wallet Unit Service of tenant {} answered {}", tenant, status);
            if (status == 401) {
                // The cached token may have been revoked or the Verifier rotated: the next request asks for another.
                systemTokens.invalidate(tenant);
                throw new OnboardingTicketException(Reason.NOT_AUTHORIZED);
            }
            throw new OnboardingTicketException(
                    answer.status.is4xxClientError() ? Reason.REJECTED : Reason.UNAVAILABLE);
        }
        ResponseBody body = answer.body;
        if (body == null || body.onboardingUrl == null || body.onboardingUrl.isBlank()
                || body.expiresIn == null || body.expiresIn <= 0) {
            log.warn("The Wallet Unit Service of tenant {} answered {} without the address or the lifetime of the ticket",
                    tenant, status);
            throw new OnboardingTicketException(Reason.UNEXPECTED_RESPONSE);
        }
        log.info("Onboarding ticket obtained for tenant {}", tenant);
        return new OnboardingTicket(body.onboardingUrl, sentAt.plusSeconds(body.expiresIn));
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private record Answer(HttpStatusCode status, ResponseBody body) {
    }

    /** The body of {@code POST /onboarding-tickets}. It holds the email and the offer. */
    private record RequestBody(
            @JsonProperty("email") String email,
            @JsonProperty("credentialOffer") String credentialOffer,
            @JsonProperty("expiresIn") long expiresIn) {

        @Override
        public String toString() {
            return "RequestBody[expiresIn=" + expiresIn + "]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ResponseBody(@JsonProperty("onboardingUrl") String onboardingUrl,
                                @JsonProperty("expiresIn") Long expiresIn) {

        @Override
        public String toString() {
            return "ResponseBody[expiresIn=" + expiresIn + "]";
        }
    }
}
