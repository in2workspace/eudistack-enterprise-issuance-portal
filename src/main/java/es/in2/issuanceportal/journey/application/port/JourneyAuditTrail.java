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

    enum Kind { STARTED, STEP_REACHED, COMPLETED, WITHDRAWN }

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
     * A request to the issuance API (design.md §3.5): the result and, when there was one, the process. No
     * email, attestation data, offer or token.
     */
    record IssuanceRequested(UUID eventId, UUID journeyId, IssuanceOutcome result, Optional<UUID> issuanceProcessId,
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

    void append(TenantId tenant, Event event);

    void append(TenantId tenant, IssuanceRequested event);

    void append(TenantId tenant, OnboardingTicketRequested event);

    void append(TenantId tenant, EmailCodeRequested event);
}
