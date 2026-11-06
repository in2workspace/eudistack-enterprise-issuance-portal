package es.in2.issuanceportal.journey.infrastructure.issuer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import es.in2.issuanceportal.journey.application.port.IssuanceApi;
import es.in2.issuanceportal.journey.application.port.IssuanceRejectedException;
import es.in2.issuanceportal.journey.application.port.IssuanceRejectedException.Reason;
import es.in2.issuanceportal.journey.application.port.IssuanceRequest;
import es.in2.issuanceportal.journey.application.port.IssuanceResult;
import es.in2.issuanceportal.journey.application.port.IssuanceUnavailableException;
import es.in2.issuanceportal.journey.application.port.SystemToken;
import es.in2.issuanceportal.journey.application.port.SystemTokenProvider;
import es.in2.issuanceportal.shared.tenant.PublicHostProperties;
import es.in2.issuanceportal.shared.tenant.TenantId;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalBiFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * {@code POST /api/v1/issuances} of the tenant's Issuer, over the internal network (design.md §3.3, AD-6).
 *
 * <p>The call carries the system token of the tenant (never the holder's: NFR-4), the tenant ({@code X-Tenant}),
 * the idempotency key and the tenant's public host and scheme as forwarded headers, so the Issuer resolves its
 * public origin: the token's {@code iss} must be that of the Verifier at the same origin, and the
 * {@code Location} of the issuance is built from it.
 *
 * <p>Each tenant has its own bulkhead (a bound on the issuances in progress) and circuit breaker (opened by
 * network errors, timeouts and 5xx, never by a 4xx): when either refuses, the Issuer is not called and the
 * answer is unavailable (SEC-25, SEC-27). The waits between retries vary at random.
 *
 * <p>A network error or timeout is retried up to {@value #MAX_RETRIES} times, and so is a 503 if the service
 * is set to; every attempt carries the same idempotency key. A 4xx is never retried, and neither is a 503 that asks to wait more than {@link #MAX_RETRY_AFTER}. Nothing of the request, the
 * offer or the token is logged, and no cause is kept: a failure only becomes a reason.
 */
@Slf4j
final class RestClientIssuanceApi implements IssuanceApi {

    static final int MAX_RETRIES = 3;
    /** How long an offer lasts when the Issuer does not say (EC-06): the validity of an issuance with wallet. */
    static final Duration DEFAULT_OFFER_VALIDITY = Duration.ofMinutes(10);
    /** The longest wait the Issuer may ask for with {@code Retry-After}; a longer one ends the retries. */
    static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(10);
    /** The waits between retries vary by up to this fraction either way, so parallel retries spread out. */
    static final double JITTER = 0.5;
    private static final String ISSUANCES_PATH = "/api/v1/issuances";

    private final RestClient http;
    private final String issuancesUrl;
    private final boolean retryOn503;
    private final PublicHostProperties publicHosts;
    private final SystemTokenProvider systemTokens;
    private final Clock clock;
    private final Retry retry;
    private final IssuerApiProperties properties;
    private final Map<TenantId, Bulkhead> bulkheads = new ConcurrentHashMap<>();
    private final Map<TenantId, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();

    RestClientIssuanceApi(RestClient http, IssuerApiProperties properties, PublicHostProperties publicHosts,
                          SystemTokenProvider systemTokens, Clock clock) {
        this.http = http;
        this.properties = properties;
        this.issuancesUrl = stripTrailingSlash(properties.issuerInternalUrl()) + ISSUANCES_PATH;
        this.retryOn503 = properties.retryOn503();
        this.publicHosts = publicHosts;
        this.systemTokens = systemTokens;
        this.clock = clock;
        this.retry = Retry.of("issuance-api", RetryConfig.<Object>custom()
                .maxAttempts(MAX_RETRIES + 1)
                .retryOnException(RetryableFailure.class::isInstance)
                .intervalBiFunction(waitBeforeRetry(properties.retryInitialWait()))
                .failAfterMaxAttempts(false)
                .build());
    }

    @Override
    public IssuanceResult issue(TenantId tenant, UUID idempotencyKey, IssuanceRequest request) {
        SystemToken token = systemTokens.tokenFor(tenant);
        RequestBody body = RequestBody.of(request);
        Bulkhead bulkhead = bulkheads.computeIfAbsent(tenant, this::newBulkhead);
        if (!bulkhead.tryAcquirePermission()) {
            log.warn("The issuance API of tenant {} has {} issuances in progress: not called", tenant,
                    properties.maxConcurrentPerTenant());
            throw new IssuanceUnavailableException(IssuanceUnavailableException.Reason.TOO_MANY_CALLS);
        }
        CircuitBreaker circuit = circuitBreakers.computeIfAbsent(tenant, this::newCircuitBreaker);
        try {
            return retry.executeSupplier(() -> circuit.executeSupplier(
                    () -> attempt(tenant, idempotencyKey, token, body)));
        } catch (CallNotPermittedException _) {
            log.warn("The circuit of the issuance API of tenant {} is {}: not called", tenant, circuit.getState());
            throw new IssuanceUnavailableException(IssuanceUnavailableException.Reason.CIRCUIT_OPEN);
        } catch (RetryableFailure e) {
            log.warn("The issuance API of tenant {} did not answer after {} retries ({})", tenant, MAX_RETRIES, e.reason);
            throw new IssuanceUnavailableException(e.reason);
        } finally {
            bulkhead.onComplete();
        }
    }

    private Bulkhead newBulkhead(TenantId tenant) {
        return Bulkhead.of("issuance-api-" + tenant.value(), BulkheadConfig.custom()
                .maxConcurrentCalls(properties.maxConcurrentPerTenant())
                .maxWaitDuration(Duration.ZERO)
                .build());
    }

    /** Only what says the Issuer is unwell counts as a failure: a 4xx and any other rejection do not. */
    private CircuitBreaker newCircuitBreaker(TenantId tenant) {
        return CircuitBreaker.of("issuance-api-" + tenant.value(), CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.circuitMinimumCalls())
                .minimumNumberOfCalls(properties.circuitMinimumCalls())
                .failureRateThreshold((float) properties.circuitFailureRatePercent())
                .waitDurationInOpenState(properties.circuitWaitInOpenState())
                .permittedNumberOfCallsInHalfOpenState(1)
                .recordException(e -> e instanceof RetryableFailure || e instanceof IssuanceUnavailableException)
                .build());
    }

    private IssuanceResult attempt(TenantId tenant, UUID idempotencyKey, SystemToken token, RequestBody body) {
        String publicHost = publicHosts.hostOf(tenant);
        Answer answer;
        try {
            answer = http.post()
                    .uri(issuancesUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.value())
                    .header("X-Tenant", tenant.value())
                    .header("X-Idempotency-Key", idempotencyKey.toString())
                    .header("X-Forwarded-Host", publicHost)
                    .header("X-Forwarded-Proto", "https")
                    .body(body)
                    .exchange((req, res) -> Answer.of(res.getStatusCode(), res.getHeaders(),
                            res.getStatusCode().is2xxSuccessful() ? res.bodyTo(ResponseBody.class) : null));
        } catch (ResourceAccessException e) {
            log.warn("The issuance API of tenant {} did not answer: {}", tenant,
                    e.getCause() == null ? "I/O error" : e.getCause().getClass().getSimpleName());
            throw new RetryableFailure(IssuanceUnavailableException.Reason.NETWORK, null);
        } catch (RestClientException _) {
            log.warn("The issuance API of tenant {} sent a response that cannot be read", tenant);
            throw new IssuanceRejectedException(Reason.UNEXPECTED_RESPONSE);
        }
        return resultOf(tenant, answer);
    }

    private IssuanceResult resultOf(TenantId tenant, Answer answer) {
        int status = answer.status.value();
        if (answer.status.is2xxSuccessful()) {
            return success(tenant, answer);
        }
        log.warn("The issuance API of tenant {} answered {}", tenant, status);
        if (status == 401) {
            // The cached token may have been revoked or the Verifier rotated: the next request asks for another.
            systemTokens.invalidate(tenant);
            throw new IssuanceRejectedException(Reason.NOT_AUTHORIZED);
        }
        if (answer.status.is4xxClientError()) {
            throw new IssuanceRejectedException(Reason.REQUEST_REJECTED);
        }
        if (status == 503 && retryOn503) {
            Duration retryAfter = RetryAfter.parse(answer.retryAfter, clock.instant()).orElse(null);
            if (retryAfter != null && retryAfter.compareTo(MAX_RETRY_AFTER) > 0) {
                log.warn("The issuance API of tenant {} asked for a wait of more than {} s: not retried", tenant,
                        MAX_RETRY_AFTER.toSeconds());
                throw new IssuanceUnavailableException(IssuanceUnavailableException.Reason.SERVER_ERROR);
            }
            throw new RetryableFailure(IssuanceUnavailableException.Reason.SERVER_ERROR, retryAfter);
        }
        if (answer.status.is5xxServerError()) {
            throw new IssuanceUnavailableException(IssuanceUnavailableException.Reason.SERVER_ERROR);
        }
        throw new IssuanceRejectedException(Reason.UNEXPECTED_RESPONSE);
    }

    private IssuanceResult success(TenantId tenant, Answer answer) {
        Optional<UUID> process = processIdOf(answer.location);
        Optional<String> offer = answer.body == null ? Optional.empty() : answer.body.offer();
        if (process.isEmpty() || offer.isEmpty()) {
            log.warn("The issuance API of tenant {} answered {} without {}", tenant, answer.status.value(),
                    process.isEmpty() ? "a valid Location" : "the credential offer");
            throw new IssuanceRejectedException(Reason.UNEXPECTED_RESPONSE);
        }
        // The Issuer says nothing about the offer's expiry (EUD-277): it lasts the validity of the issuance.
        Instant expiresAt = clock.instant().plus(DEFAULT_OFFER_VALIDITY);
        log.info("Issuance {} obtained for tenant {}", process.get(), tenant);
        return new IssuanceResult(process.get(), offer.get(), expiresAt);
    }

    /** The process is the last segment of the {@code Location}, which must be a UUID in its canonical form. */
    private static Optional<UUID> processIdOf(String location) {
        if (location == null || location.isBlank()) {
            return Optional.empty();
        }
        try {
            String path = URI.create(location.trim()).getPath();
            String segment = path.substring(path.lastIndexOf('/') + 1);
            UUID id = UUID.fromString(segment);
            return id.toString().equalsIgnoreCase(segment) ? Optional.of(id) : Optional.empty();
        } catch (IllegalArgumentException | NullPointerException _) {
            return Optional.empty();
        }
    }

    /**
     * What the Issuer asked for with {@code Retry-After}, or else 1, 2 and 4 s (EC-07), each varied at random
     * by up to {@value #JITTER} either way (SEC-25).
     */
    private static IntervalBiFunction<Object> waitBeforeRetry(Duration initialWait) {
        return (retries, outcome) -> {
            if (outcome.isLeft() && outcome.getLeft() instanceof RetryableFailure failure && failure.retryAfter != null) {
                return failure.retryAfter.toMillis();
            }
            return withJitter(initialWait.toMillis() << (retries - 1));
        };
    }

    static long withJitter(long millis) {
        double factor = 1 + JITTER * (2 * ThreadLocalRandom.current().nextDouble() - 1);
        return Math.round(millis * factor);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** A failed attempt that may be tried again. */
    private static final class RetryableFailure extends RuntimeException {

        private final transient IssuanceUnavailableException.Reason reason;
        private final transient Duration retryAfter;

        RetryableFailure(IssuanceUnavailableException.Reason reason, Duration retryAfter) {
            super(null, null, false, false);
            this.reason = reason;
            this.retryAfter = retryAfter;
        }
    }

    /** What the portal reads of a response. */
    private record Answer(HttpStatusCode status, String location, String retryAfter, ResponseBody body) {

        static Answer of(HttpStatusCode status, HttpHeaders headers, ResponseBody body) {
            return new Answer(status, headers.getFirst(HttpHeaders.LOCATION),
                    headers.getFirst(HttpHeaders.RETRY_AFTER), body);
        }
    }

    /** The body of {@code POST /api/v1/issuances}, as the Issuer reads it. It holds personal data. */
    private record RequestBody(
            @JsonProperty("credential_configuration_id") String credentialConfigurationId,
            @JsonProperty("payload") Map<String, Object> payload,
            @JsonProperty("email") String email,
            @JsonProperty("delivery") String delivery,
            @JsonProperty("grant_type") String grantType) {

        static RequestBody of(IssuanceRequest request) {
            return new RequestBody(request.credentialConfigurationId(), request.payload(), request.email(),
                    request.delivery(), request.grantType());
        }

        @Override
        public String toString() {
            return "RequestBody[credentialConfigurationId=" + credentialConfigurationId + "]";
        }
    }

    /** {@code {"responses":[{"channel":"ui","status":200,"body":{"credential_offer_uri":"..."}}]}}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ResponseBody(List<Channel> responses) {

        Optional<String> offer() {
            if (responses == null || responses.isEmpty() || responses.get(0) == null
                    || responses.get(0).body() == null) {
                return Optional.empty();
            }
            String uri = responses.get(0).body().credentialOfferUri();
            return uri == null || uri.isBlank() ? Optional.empty() : Optional.of(uri);
        }

        @Override
        public String toString() {
            return "ResponseBody[]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Channel(Offer body) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Offer(@JsonProperty("credential_offer_uri") String credentialOfferUri) {

        @Override
        public String toString() {
            return "Offer[]";
        }
    }
}
