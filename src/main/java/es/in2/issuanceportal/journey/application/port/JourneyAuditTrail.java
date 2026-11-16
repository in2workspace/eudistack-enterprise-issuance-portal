package es.in2.issuanceportal.journey.application.port;

import es.in2.issuanceportal.journey.domain.JourneyState;
import es.in2.issuanceportal.journey.domain.JourneyStep;
import es.in2.issuanceportal.journey.domain.WithdrawalReason;
import es.in2.issuanceportal.shared.tenant.TenantId;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Trace of every change of a journey (prd.md FR-13, design.md §3.2). Events carry identifiers, states
 * and steps only: never identity, source data, email or attestation data.
 */
public interface JourneyAuditTrail {

    enum Kind { STARTED, STEP_REACHED, COMPLETED, WITHDRAWN, ENDED }

    record Event(
            UUID eventId,
            Kind kind,
            UUID journeyId,
            JourneyState state,
            JourneyStep step,
            Optional<WithdrawalReason> withdrawalReason,
            Instant occurredAt) {
    }

    /** What came of a request to the issuance API. */
    enum IssuanceOutcome {

        /** The Issuer created the process and gave the offer. */
        ISSUED("issued"),
        /** The Issuer refused the request or answered without what the portal needs. */
        REJECTED("rejected"),
        /** The Issuer did not answer, also after the retries. */
        UNAVAILABLE("unavailable"),
        /** The tenant has no system token, so the Issuer was not asked. */
        SYSTEM_TOKEN_UNAVAILABLE("system_token_unavailable"),
        /** The Issuer created the process, but the onboarding ticket for its offer was not created (AC-12). */
        ONBOARDING_FAILED("onboarding_failed");

        private final String code;

        IssuanceOutcome(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /**
     * A request to the issuance API (design.md §3.5): the request of the journey by its opaque id, the result and,
     * when there was one, the process. No email, attestation data, offer or token.
     */
    record IssuanceRequested(UUID eventId, UUID journeyId, String requestId, IssuanceOutcome result,
                             Optional<UUID> issuanceProcessId,
                             Instant occurredAt) {
    }

    /** What came of a request to the Wallet Unit Service for an onboarding ticket. */
    enum TicketOutcome {

        CREATED("created"),
        FAILED("failed");

        private final String code;

        TicketOutcome(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /**
     * A request to the Wallet Unit Service for an onboarding ticket (design.md §3.5): the result only. No email,
     * offer, onboarding address or token.
     */
    record OnboardingTicketRequested(UUID eventId, UUID journeyId, TicketOutcome result, Instant occurredAt) {
    }

    /** What came of a request to send the holder's email a verification code. */
    enum EmailCodeOutcome {

        /** The code email was handed over for delivery. */
        SENT("sent"),
        /** Less than 180 seconds since the journey's last send. */
        RESEND_TOO_SOON("resend_too_soon"),
        /** The journey already sent its 3 codes. */
        TOO_MANY_CODES("too_many_codes"),
        /** The client address already sent 10 codes in the last hour. */
        TOO_MANY_REQUESTS("too_many_requests"),
        /** The mail provider did not take the email, or did not answer in time. */
        EMAIL_UNAVAILABLE("email_unavailable");

        private final String code;

        EmailCodeOutcome(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /**
     * A request to send a verification code (design.md §3.5): the result only. No email, code or hash.
     */
    record EmailCodeRequested(UUID eventId, UUID journeyId, EmailCodeOutcome result, Instant occurredAt) {
    }

    /** What came of checking the code the holder typed. */
    enum EmailCodeCheck {

        /** The right code: the email is now the journey's verified email. */
        VERIFIED("verified"),
        INVALID_CODE("invalid_code"),
        /** More than 10 minutes old, or voided by a newer code. */
        EXPIRED_CODE("expired_code"),
        /** The 5 attempts of the code are spent. */
        TOO_MANY_ATTEMPTS("too_many_attempts");

        private final String code;

        EmailCodeCheck(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** The check of a verification code (design.md section 3.5): the result only. No email, code or hash. */
    record EmailCodeChecked(UUID eventId, UUID journeyId, EmailCodeCheck result, Instant occurredAt) {
    }

    /** What came of asking the issuance API for a new offer of a request (design.md §3.4, §3.5). */
    enum RenewalOutcome {

        /** The Issuer gave a new offer. */
        RENEWED("renewed"),
        /** The Issuer said the process can no longer be renewed: the request is saved. */
        REQUEST_SAVED("request_saved"),
        /** The Issuer refused the request, or the tenant has no system token. */
        REJECTED("rejected"),
        /** The Issuer did not answer, also after the retries. */
        UNAVAILABLE("unavailable");

        private final String code;

        RenewalOutcome(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /**
     * A request for a new offer (design.md §3.5): the request of the journey by its opaque id and the result. No
     * email, offer, address or token.
     */
    record OfferRenewalRequested(UUID eventId, UUID journeyId, String requestId, RenewalOutcome result,
                                 Instant occurredAt) {
    }

    /** Why a request became saved (design.md §3.4, §3.8). */
    enum SavedOrigin {

        /** The holder pressed "I have saved it". */
        HOLDER_DECLARED("holder_declared"),
        /** The Issuer said the offer can no longer be renewed. */
        OFFER_NOT_RENEWABLE("offer_not_renewable");

        private final String code;

        SavedOrigin(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** A request became saved (design.md §3.5): the opaque id and why. No offer, address or data. */
    record RequestSaved(UUID eventId, UUID journeyId, String requestId, SavedOrigin origin, Instant occurredAt) {
    }

    void append(TenantId tenant, Event event);

    void append(TenantId tenant, RequestSaved event);

    void append(TenantId tenant, OfferRenewalRequested event);

    void append(TenantId tenant, IssuanceRequested event);

    void append(TenantId tenant, OnboardingTicketRequested event);

    void append(TenantId tenant, EmailCodeRequested event);

    void append(TenantId tenant, EmailCodeChecked event);
}
