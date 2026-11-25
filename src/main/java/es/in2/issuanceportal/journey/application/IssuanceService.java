package es.in2.issuanceportal.journey.application;

import es.in2.issuanceportal.journey.api.JourneyAccess;
import es.in2.issuanceportal.journey.application.port.IssuanceApi;
import es.in2.issuanceportal.journey.application.port.IssuanceRejectedException;
import es.in2.issuanceportal.journey.application.port.IssuanceRequest;
import es.in2.issuanceportal.journey.application.port.IssuanceResult;
import es.in2.issuanceportal.journey.application.port.IssuanceUnavailableException;
import es.in2.issuanceportal.journey.application.port.JourneyAuditTrail;
import es.in2.issuanceportal.journey.application.port.JourneyIssuanceRepository;
import es.in2.issuanceportal.journey.application.port.JourneyRepository;
import es.in2.issuanceportal.journey.application.port.OfferNotRenewableException;
import es.in2.issuanceportal.journey.application.port.OnboardingTicket;
import es.in2.issuanceportal.journey.application.port.OnboardingTicketException;
import es.in2.issuanceportal.journey.application.port.OnboardingTickets;
import es.in2.issuanceportal.shared.systemtoken.SystemTokenUnavailableException;
import es.in2.issuanceportal.journey.domain.IncompletePlacementException;
import es.in2.issuanceportal.journey.domain.IssuancePayload;
import es.in2.issuanceportal.journey.domain.Journey;
import es.in2.issuanceportal.journey.domain.JourneyIssuance;
import es.in2.issuanceportal.journey.domain.JourneyState;
import es.in2.issuanceportal.journey.domain.config.Onboarding;
import es.in2.issuanceportal.journey.domain.config.ServiceSettings;
import es.in2.issuanceportal.session.api.EmailRecord;
import es.in2.issuanceportal.session.api.EmailVerificationRecord;
import es.in2.issuanceportal.session.api.EntitlementRecord;
import es.in2.issuanceportal.session.api.HolderIdentity;
import es.in2.issuanceportal.session.api.HolderRef;
import es.in2.issuanceportal.session.api.InvalidSectionException;
import es.in2.issuanceportal.session.api.IssuanceRecord;
import es.in2.issuanceportal.session.api.JourneySessions;
import es.in2.issuanceportal.session.api.Section;
import es.in2.issuanceportal.shared.error.ProblemCode;
import es.in2.issuanceportal.shared.error.ProblemException;
import es.in2.issuanceportal.shared.tenant.TenantId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The holder's confirmation of one request: requests its issuance as a system and hands the offer back
 * (design.md §3.3, §3.7, AD-12, AD-16). Each request asks the Issuer once, with one idempotency key generated the
 * first time and kept with the request in the journey, with the verified email it was created with, for every retry
 * (EC-02), so a request ends with at most one issuance process, and two requests never share a key. If the holder
 * verifies another email before a request has its process, the key is replaced by a new one for that email; once
 * the process exists the request keeps its key and its process.
 *
 * <p>The journey's row is locked in two short transactions and never while the Issuer is asked: the first
 * checks the state and keeps the key, the second keeps the process and the offer and, with the first offer of the
 * journey, completes it. No database connection waits for an external system. Two confirmations of the same request
 * at the same time share the key the first one stored (EC-03); the Issuer's idempotency gives both the same process,
 * and the one that finishes second answers with the offer the first stored. The journey stays active after the
 * first offer: the other requests are confirmed in the same journey, with the email verified once.
 *
 * <p>In a tenant with onboarding, the ticket is requested between the Issuer's answer and the second
 * transaction, also outside any transaction, so an offer that cannot be turned into a ticket is never stored
 * nor given: the second transaction then keeps only the process, and the journey stays where it was
 * (AC-12, design.md §3.4).
 */
@Slf4j
@Service
public class IssuanceService {

    static final String DELIVERY = "ui";
    static final String GRANT_TYPE = "authorization_code";
    /** How long before its offer the onboarding ticket expires (AC-11). */
    static final Duration TICKET_MARGIN = Duration.ofSeconds(60);

    private final JourneyAccess access;
    private final JourneyRepository journeys;
    private final JourneyIssuanceRepository issuances;
    private final JourneySessions sessions;
    private final PortalConfigService configs;
    private final IssuanceApi issuance;
    private final OnboardingTickets tickets;
    private final JourneyAuditTrail trail;
    private final TransactionOperations transactions;
    private final Clock clock;

    IssuanceService(JourneyAccess access, JourneyRepository journeys, JourneyIssuanceRepository issuances,
                    JourneySessions sessions,
                    PortalConfigService configs, IssuanceApi issuance, OnboardingTickets tickets,
                    JourneyAuditTrail trail, TransactionOperations transactions, Clock clock) {
        this.access = access;
        this.journeys = journeys;
        this.issuances = issuances;
        this.sessions = sessions;
        this.configs = configs;
        this.issuance = issuance;
        this.tickets = tickets;
        this.trail = trail;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * The offer the holder gets. The issuance process is never part of it (AC-03).
     *
     * @param credentialOfferUri the offer exactly as the Issuer returned it
     * @param expiresAt          when the offer stops being valid
     * @param onboardingUrl      the wallet onboarding address; {@code null} in a tenant without onboarding
     * @param onboardingExpiresAt when that address stops being valid, before the offer; {@code null} with no address
     */
    public record Offer(String credentialOfferUri, Instant expiresAt, String onboardingUrl,
                        Instant onboardingExpiresAt) {

        @Override
        public String toString() {
            return "Offer[expiresAt=" + expiresAt + ", onboardingExpiresAt=" + onboardingExpiresAt
                    + ", credentialOfferUri and onboardingUrl redacted]";
        }
    }

    /** What the first transaction leaves ready: a new issuance, or the new offer of a request that has its process. */
    private sealed interface Plan permits Prepared, Renewal {
    }

    /** A request without a process: the issuance to ask for. */
    private record Prepared(UUID idempotencyKey, IssuanceRequest request, Onboarding onboarding, String requestId)
            implements Plan {
    }

    /** A request with its process: the new offer to ask for (design.md §3.4). */
    private record Renewal(String requestId, UUID processId, String email, Onboarding onboarding) implements Plan {
    }

    /**
     * The email in the request is the one the holder verified with a code (EUD-358); the data, the request's own.
     *
     * @param requestId the request the holder confirms, as the journey returns it; a blank one or one of more than
     *                  64 characters is {@code bad_request}
     */
    public Offer confirm(TenantId tenant, HolderRef holder, String requestId) {
        requireValid(requestId);
        access.requireActive(tenant, holder);
        UUID journeyId = sessions.journeyOf(tenant, holder)
                .orElseThrow(() -> new ProblemException(ProblemCode.JOURNEY_CLOSED));
        Plan plan = Objects.requireNonNull(transactions.execute(status -> prepare(tenant, journeyId, requestId)));
        // A request that already has its process is not issued again: it follows POST /journey/offers (§3.7 step 2).
        return switch (plan) {
            case Prepared prepared -> issueNew(tenant, journeyId, prepared);
            case Renewal renewal -> renew(tenant, journeyId, renewal);
        };
    }

    /**
     * A new offer for a request that has its process (design.md §3.4): only once the address delivered for it
     * expired, or none was delivered because the ticket failed. No new process is created.
     *
     * @throws ProblemException {@code step_not_allowed} when the request has no process, is saved, or its address
     *                          has not expired; {@code onboarding_unavailable} when the ticket cannot be obtained
     */
    public Offer newOffer(TenantId tenant, HolderRef holder, String requestId) {
        requireValid(requestId);
        access.requireActive(tenant, holder);
        UUID journeyId = sessions.journeyOf(tenant, holder)
                .orElseThrow(() -> new ProblemException(ProblemCode.JOURNEY_CLOSED));
        Renewal renewal = Objects.requireNonNull(transactions.execute(status -> {
            Journey journey = lock(tenant, journeyId);
            if (!journey.isActive()) {
                throw new ProblemException(ProblemCode.JOURNEY_CLOSED);
            }
            String address = verifiedEmail(tenant, journeyId);
            return renewalOf(tenant, journeyId, requestId, address, settings(tenant));
        }));
        return renew(tenant, journeyId, renewal);
    }

    private static void requireValid(String requestId) {
        if (requestId == null || requestId.isBlank() || requestId.length() > JourneyIssuance.MAX_REQUEST_ID_LENGTH) {
            throw new ProblemException(ProblemCode.BAD_REQUEST);
        }
    }

    private Offer issueNew(TenantId tenant, UUID journeyId, Prepared prepared) {
        String requestId = prepared.requestId();
        IssuanceResult issued = request(tenant, journeyId, prepared);
        boolean ticketNeeded = prepared.onboarding().enabled();
        OnboardingTicket ticket = ticketNeeded
                ? requestTicket(tenant, journeyId, prepared.onboarding(), prepared.request().email(), issued) : null;
        trace(tenant, journeyId, requestId, ticketNeeded && ticket == null
                ? JourneyAuditTrail.IssuanceOutcome.ONBOARDING_FAILED : JourneyAuditTrail.IssuanceOutcome.ISSUED,
                issued.processId());
        Optional<Offer> offer = Objects.requireNonNull(transactions.execute(status -> finish(tenant,
                journeyId, prepared, issued, ticketNeeded, ticket)));
        // The process is kept, the offer is not given: without the ticket the holder could not use it in the wallet.
        return offer.orElseThrow(() -> new ProblemException(ProblemCode.ONBOARDING_UNAVAILABLE));
    }

    /**
     * First transaction: with the journey locked, checks what the request needs, among it the verified email and
     * that the request is one of the journey that can be issued, and keeps a new idempotency key if the request has
     * none for that email. Anything that makes it fail leaves the journey as it was.
     */
    private Plan prepare(TenantId tenant, UUID journeyId, String requestId) {
        Journey journey = lock(tenant, journeyId);
        if (!journey.isActive()) {
            throw new ProblemException(ProblemCode.JOURNEY_CLOSED);
        }
        HolderIdentity identity = identity(tenant, journeyId);
        EntitlementRecord entitlement = entitlement(tenant, journeyId);
        // Always the one in the session: a retry after onboarding_unavailable needs no new code (EC-05).
        String address = verifiedEmail(tenant, journeyId);
        ServiceSettings settings = settings(tenant);
        EntitlementRecord.Request confirmed = entitlement.request(requestId)
                .orElseThrow(() -> new ProblemException(ProblemCode.STEP_NOT_ALLOWED));
        if (processOf(tenant, journeyId, requestId).isPresent()) {
            return renewalOf(tenant, journeyId, requestId, address, settings);
        }
        requireAvailable(confirmed);
        Map<String, Object> payload = payload(tenant, settings, identity, confirmed.claims());
        IssuanceRecord section = readIssuance(tenant, journeyId).orElseGet(IssuanceRecord::empty);
        Optional<IssuanceRecord.Entry> issued = section.entry(requestId);
        UUID key;
        if (issued.isPresent() && issued.get().isForEmail(address)) {
            key = issued.get().idempotencyKey();
        } else {
            // First confirmation of the request, or the holder verified another email after a failed one: the Issuer
            // has no process for the old key (the request has none), so a new key goes with the new email (EUD-358).
            key = UUID.randomUUID();
            sessions.write(tenant, journeyId, Section.ISSUANCE,
                    section.with(requestId, IssuanceRecord.Entry.withKey(key, address)).toJson());
        }
        return new Prepared(key, new IssuanceRequest(settings.attestationType(), payload, address,
                DELIVERY, GRANT_TYPE), settings.onboarding(), requestId);
    }

    private Optional<JourneyIssuance> processOf(TenantId tenant, UUID journeyId, String requestId) {
        return issuances.findByJourney(tenant, journeyId).stream()
                .filter(process -> process.requestId().equals(requestId)).findFirst();
    }

    /** A request without a process is confirmed only if the source says it is available; otherwise {@code step_not_allowed}. */
    private static void requireAvailable(EntitlementRecord.Request request) {
        if (request.status() != EntitlementRecord.Status.AVAILABLE) {
            throw new ProblemException(ProblemCode.STEP_NOT_ALLOWED);
        }
    }

    /**
     * The new offer of a request with its process, if the address delivered for it expired or none was delivered.
     * A request that has no process, is saved, is under review because the holder said its data is not correct, or
     * whose address still works is {@code step_not_allowed}: the current offer keeps serving (design.md §3.4 step 2).
     */
    private Renewal renewalOf(TenantId tenant, UUID journeyId, String requestId, String address,
                              ServiceSettings settings) {
        JourneyIssuance process = processOf(tenant, journeyId, requestId)
                .orElseThrow(() -> new ProblemException(ProblemCode.STEP_NOT_ALLOWED));
        if (process.isSaved()) {
            throw new ProblemException(ProblemCode.STEP_NOT_ALLOWED);
        }
        // The holder said its data is not correct: no more offers of it in this journey (ac.md AC-06).
        boolean underReview = entitlement(tenant, journeyId).request(requestId)
                .map(request -> request.status() == EntitlementRecord.Status.UNDER_REVIEW).orElse(false);
        if (underReview) {
            throw new ProblemException(ProblemCode.STEP_NOT_ALLOWED);
        }
        Optional<IssuanceRecord.Entry> entry = readIssuance(tenant, journeyId)
                .flatMap(section -> section.entry(requestId));
        if (entry.isPresent() && entry.get().offerUri().isPresent()
                && clock.instant().isBefore(deliveredAddressExpiry(entry.get()))) {
            throw new ProblemException(ProblemCode.STEP_NOT_ALLOWED);
        }
        return new Renewal(requestId, process.issuanceProcessId(), address, settings.onboarding());
    }

    /** The address the interface was given: the onboarding address in a tenant with tickets, else the offer. */
    private static Instant deliveredAddressExpiry(IssuanceRecord.Entry entry) {
        return entry.onboardingExpiresAt() != null ? entry.onboardingExpiresAt() : entry.offerExpiresAt();
    }

    /**
     * The email the holder verified: the one in the verified {@code EMAIL_VERIFICATION} section and, as the
     * journey's email, in the {@code EMAIL} section. An email section alone is never enough (NFR-2, AC-08):
     * without the verification, or with another address in it, the confirmation is not allowed and nothing is
     * requested.
     */
    private String verifiedEmail(TenantId tenant, UUID journeyId) {
        String emailSection = sessions.read(tenant, journeyId, Section.EMAIL)
                .orElseThrow(() -> new ProblemException(ProblemCode.STEP_NOT_ALLOWED));
        String verificationSection = sessions.read(tenant, journeyId, Section.EMAIL_VERIFICATION)
                .orElseThrow(() -> new ProblemException(ProblemCode.STEP_NOT_ALLOWED));
        try {
            String address = EmailRecord.fromJson(emailSection).address();
            EmailVerificationRecord verification = EmailVerificationRecord.fromJson(verificationSection);
            boolean verified = verification.codeHash() == null && address.equals(verification.pendingEmail());
            if (!verified) {
                log.info("The email of the journey session is not the verified one: confirmation not allowed");
                throw new ProblemException(ProblemCode.STEP_NOT_ALLOWED);
            }
            return address;
        } catch (InvalidSectionException e) {
            log.warn("Email of the journey session is invalid at {}", e.field());
            throw new ProblemException(ProblemCode.STEP_NOT_ALLOWED);
        }
    }

    /** Outside any transaction. Every failure leaves the journey in progress, with its key, for the next attempt. */
    private IssuanceResult request(TenantId tenant, UUID journeyId, Prepared prepared) {
        try {
            return issuance.issue(tenant, prepared.idempotencyKey(), prepared.request());
        } catch (SystemTokenUnavailableException e) {
            log.warn("No system token of tenant {} to issue for journey {}: {}", tenant, journeyId, e.reason());
            trace(tenant, journeyId, prepared.requestId(), JourneyAuditTrail.IssuanceOutcome.SYSTEM_TOKEN_UNAVAILABLE, null);
            throw new ProblemException(ProblemCode.ISSUANCE_UNAVAILABLE);
        } catch (IssuanceRejectedException e) {
            log.warn("Issuance of journey {} rejected: {}", journeyId, e.reason());
            trace(tenant, journeyId, prepared.requestId(), JourneyAuditTrail.IssuanceOutcome.REJECTED, null);
            throw new ProblemException(ProblemCode.ISSUANCE_REJECTED);
        } catch (IssuanceUnavailableException e) {
            log.warn("Issuance of journey {} unavailable: {}", journeyId, e.reason());
            trace(tenant, journeyId, prepared.requestId(), JourneyAuditTrail.IssuanceOutcome.UNAVAILABLE, null);
            throw new ProblemException(ProblemCode.ISSUANCE_UNAVAILABLE);
        }
    }

    /**
     * Outside any transaction. Any failure, or an offer with too little life left for a ticket that expires
     * before it, gives no ticket: the process the Issuer created stays for the next attempt.
     *
     * @return the ticket, or {@code null} when it was not created
     */
    private OnboardingTicket requestTicket(TenantId tenant, UUID journeyId, Onboarding onboarding, String email,
                                           IssuanceResult issued) {
        Duration expiresIn = Duration.between(clock.instant(), issued.offerExpiresAt()).minus(TICKET_MARGIN);
        if (expiresIn.compareTo(Duration.ofSeconds(1)) < 0) {
            log.warn("Offer of journey {} has too little life left for an onboarding ticket: not requested", journeyId);
            traceTicket(tenant, journeyId, JourneyAuditTrail.TicketOutcome.FAILED);
            return null;
        }
        try {
            OnboardingTicket ticket = tickets.request(tenant, onboarding.walletUnitServiceUrl(),
                    email, issued.credentialOfferUri(), expiresIn);
            traceTicket(tenant, journeyId, JourneyAuditTrail.TicketOutcome.CREATED);
            return ticket;
        } catch (OnboardingTicketException e) {
            log.warn("Onboarding ticket of journey {} not created: {}", journeyId, e.reason());
            traceTicket(tenant, journeyId, JourneyAuditTrail.TicketOutcome.FAILED);
            return null;
        }
    }

    /** Outside any transaction, like the issuance: the journey is not locked while the Issuer and the tickets answer. */
    private Offer renew(TenantId tenant, UUID journeyId, Renewal plan) {
        IssuanceResult renewed = askForNewOffer(tenant, journeyId, plan);
        boolean ticketNeeded = plan.onboarding().enabled();
        OnboardingTicket ticket = ticketNeeded
                ? requestTicket(tenant, journeyId, plan.onboarding(), plan.email(), renewed) : null;
        Optional<Offer> offer = Objects.requireNonNull(transactions.execute(status ->
                finishRenewal(tenant, journeyId, plan, renewed, ticketNeeded, ticket)));
        return offer.orElseThrow(() -> new ProblemException(ProblemCode.ONBOARDING_UNAVAILABLE));
    }

    private IssuanceResult askForNewOffer(TenantId tenant, UUID journeyId, Renewal plan) {
        try {
            IssuanceResult renewed = issuance.renewOffer(tenant, plan.processId());
            traceRenewal(tenant, journeyId, plan.requestId(), JourneyAuditTrail.RenewalOutcome.RENEWED);
            return renewed;
        } catch (OfferNotRenewableException _) {
            traceRenewal(tenant, journeyId, plan.requestId(), JourneyAuditTrail.RenewalOutcome.REQUEST_SAVED);
            saveBecauseNotRenewable(tenant, journeyId, plan.requestId());
            // Outside the transaction that saved it, so the answer does not undo it.
            throw new ProblemException(ProblemCode.REQUEST_SAVED);
        } catch (SystemTokenUnavailableException e) {
            log.warn("No system token of tenant {} to renew an offer of journey {}: {}", tenant, journeyId, e.reason());
            traceRenewal(tenant, journeyId, plan.requestId(), JourneyAuditTrail.RenewalOutcome.UNAVAILABLE);
            throw new ProblemException(ProblemCode.ISSUANCE_UNAVAILABLE);
        } catch (IssuanceRejectedException e) {
            log.warn("Offer renewal of journey {} rejected: {}", journeyId, e.reason());
            traceRenewal(tenant, journeyId, plan.requestId(), JourneyAuditTrail.RenewalOutcome.REJECTED);
            throw new ProblemException(ProblemCode.ISSUANCE_REJECTED);
        } catch (IssuanceUnavailableException e) {
            log.warn("Offer renewal of journey {} unavailable: {}", journeyId, e.reason());
            traceRenewal(tenant, journeyId, plan.requestId(), JourneyAuditTrail.RenewalOutcome.UNAVAILABLE);
            throw new ProblemException(ProblemCode.ISSUANCE_UNAVAILABLE);
        }
    }

    /**
     * Second transaction of a new offer. With the ticket, or in a tenant without onboarding, keeps the new offer in
     * the request's entry and, if it is the journey's first offer, completes it. Without a ticket that was needed the
     * new offer is not kept, and the old one, which the Issuer voided, is dropped so the next call can renew again:
     * the journey stays as it was.
     *
     * @return the offer to give, or empty when the ticket was needed and not created
     */
    // Each argument is a different step of the renewal: a record would only rename them.
    @SuppressWarnings("java:S107")
    private Optional<Offer> finishRenewal(TenantId tenant, UUID journeyId, Renewal plan, IssuanceResult renewed,
                                          boolean ticketNeeded, OnboardingTicket ticket) {
        Journey journey = lock(tenant, journeyId);
        if (!journey.isActive()) {
            log.warn("Journey {} closed while its offer was renewed", journeyId);
            throw new ProblemException(ProblemCode.JOURNEY_CLOSED);
        }
        JourneyIssuance process = processOf(tenant, journeyId, plan.requestId())
                .orElseThrow(() -> new ProblemException(ProblemCode.STEP_NOT_ALLOWED));
        if (process.isSaved()) {
            throw new ProblemException(ProblemCode.STEP_NOT_ALLOWED);
        }
        IssuanceRecord section = readIssuance(tenant, journeyId).orElseGet(IssuanceRecord::empty);
        IssuanceRecord.Entry before = section.entry(plan.requestId())
                .orElseGet(() -> IssuanceRecord.Entry.withKey(UUID.randomUUID(), plan.email()));
        if (ticketNeeded && ticket == null) {
            sessions.write(tenant, journeyId, Section.ISSUANCE, section.with(plan.requestId(),
                    IssuanceRecord.Entry.withKey(before.idempotencyKey(), before.email())).toJson());
            return Optional.empty();
        }
        String onboardingUrl = ticket == null ? null : ticket.onboardingUrl();
        Instant onboardingExpiresAt = ticket == null ? null : ticket.expiresAt();
        sessions.write(tenant, journeyId, Section.ISSUANCE, section.with(plan.requestId(),
                new IssuanceRecord.Entry(before.idempotencyKey(), before.email(), renewed.credentialOfferUri(),
                        renewed.offerExpiresAt(), onboardingUrl, onboardingExpiresAt)).toJson());
        if (journey.isInProgress()) {
            journey.complete(clock.instant());
            if (!journeys.update(tenant, journey, JourneyState.IN_PROGRESS)) {
                throw new ProblemException(ProblemCode.JOURNEY_CLOSED);
            }
            audit(tenant, JourneyAuditTrail.Kind.COMPLETED, journey);
        }
        return Optional.of(new Offer(renewed.credentialOfferUri(), renewed.offerExpiresAt(), onboardingUrl,
                onboardingExpiresAt));
    }

    /**
     * The Issuer says the process is no longer a draft: the request is saved, whether or not the holder saved it.
     * Its offer and onboarding address are deleted, no other issuance is ever asked for it, and a journey still in
     * progress is completed, as with any request that has its process (design.md §3.4 step 5).
     */
    private void saveBecauseNotRenewable(TenantId tenant, UUID journeyId, String requestId) {
        transactions.executeWithoutResult(status -> {
            Journey journey = lock(tenant, journeyId);
            if (!journey.isActive()) {
                throw new ProblemException(ProblemCode.JOURNEY_CLOSED);
            }
            markSaved(tenant, journeyId, requestId, JourneyAuditTrail.SavedOrigin.OFFER_NOT_RENEWABLE);
            if (journey.isInProgress()) {
                journey.complete(clock.instant());
                if (!journeys.update(tenant, journey, JourneyState.IN_PROGRESS)) {
                    throw new ProblemException(ProblemCode.JOURNEY_CLOSED);
                }
                audit(tenant, JourneyAuditTrail.Kind.COMPLETED, journey);
            }
        });
    }

    /**
     * The holder declares the attestation of a request saved (design.md §3.8, AD-17). The request must be in offer
     * with its offer delivered; it becomes saved in this journey only. Never asks the Issuer and never checks the
     * wallet: a declaration made without saving costs only this journey, because a new one gets the request again.
     * Counts as activity.
     *
     * @throws ProblemException {@code bad_request} for an invalid id; {@code step_not_allowed} for a request that is
     *                          not one of the journey, not in offer, saved already, or without an offer delivered
     */
    public void declareSaved(TenantId tenant, HolderRef holder, String requestId) {
        requireValid(requestId);
        access.requireActive(tenant, holder);
        UUID journeyId = sessions.journeyOf(tenant, holder)
                .orElseThrow(() -> new ProblemException(ProblemCode.JOURNEY_CLOSED));
        transactions.executeWithoutResult(status -> {
            Journey journey = lock(tenant, journeyId);
            if (!journey.isActive()) {
                throw new ProblemException(ProblemCode.JOURNEY_CLOSED);
            }
            JourneyIssuance process = processOf(tenant, journeyId, requestId)
                    .orElseThrow(() -> new ProblemException(ProblemCode.STEP_NOT_ALLOWED));
            boolean delivered = readIssuance(tenant, journeyId).flatMap(section -> section.entry(requestId))
                    .flatMap(IssuanceRecord.Entry::offerUri).isPresent();
            if (process.isSaved() || !delivered) {
                throw new ProblemException(ProblemCode.STEP_NOT_ALLOWED);
            }
            markSaved(tenant, journeyId, requestId, JourneyAuditTrail.SavedOrigin.HOLDER_DECLARED);
        });
    }

    /** Sets {@code saved_at}, deletes the request's offer and onboarding address, and traces why. Journey locked. */
    private void markSaved(TenantId tenant, UUID journeyId, String requestId, JourneyAuditTrail.SavedOrigin origin) {
        issuances.markSaved(tenant, journeyId, requestId, clock.instant());
        IssuanceRecord section = readIssuance(tenant, journeyId).orElseGet(IssuanceRecord::empty);
        section.entry(requestId).ifPresent(entry -> sessions.write(tenant, journeyId, Section.ISSUANCE,
                section.with(requestId, IssuanceRecord.Entry.withKey(entry.idempotencyKey(), entry.email())).toJson()));
        try {
            trail.append(tenant, new JourneyAuditTrail.RequestSaved(UUID.randomUUID(), journeyId, requestId, origin,
                    clock.instant()));
        } catch (RuntimeException e) {
            log.error("Could not record the saved request of journey {}", journeyId, e);
        }
    }

    private void traceRenewal(TenantId tenant, UUID journeyId, String requestId,
                              JourneyAuditTrail.RenewalOutcome outcome) {
        try {
            trail.append(tenant, new JourneyAuditTrail.OfferRenewalRequested(UUID.randomUUID(), journeyId, requestId,
                    outcome, clock.instant()));
        } catch (RuntimeException e) {
            log.error("Could not record the offer renewal of journey {}", journeyId, e);
        }
    }

    /**
     * Second transaction. With the ticket, or in a tenant without onboarding, keeps the process and the offer
     * (and the onboarding address) of the request and, if it is the journey's first offer, completes the journey.
     * Without a ticket that was needed, keeps only the process: no offer and no address, and the journey stays as
     * it was (in progress with the email verified, or completed). If a parallel confirmation of the same request
     * recorded its process first, answers with the offer that one stored.
     *
     * @param ticketNeeded whether the tenant uses onboarding tickets
     * @param ticket       {@code null} when the tenant has no onboarding or the ticket was not created
     * @return the offer to give, or empty when the ticket was needed and not created
     */
    private Optional<Offer> finish(TenantId tenant, UUID journeyId, Prepared prepared, IssuanceResult issued,
                                   boolean ticketNeeded, OnboardingTicket ticket) {
        Journey journey = lock(tenant, journeyId);
        if (!journey.isActive()) {
            // Withdrawn, ended or expired while the Issuer was asked. The process it created stays unused.
            log.warn("Journey {} closed while its issuance was requested", journeyId);
            throw new ProblemException(ProblemCode.JOURNEY_CLOSED);
        }
        boolean recorded = issuances.findByJourney(tenant, journeyId).stream()
                .anyMatch(process -> process.requestId().equals(prepared.requestId()));
        if (recorded) {
            return Optional.of(storedOffer(tenant, journeyId, prepared.requestId()));
        }
        Instant now = clock.instant();
        issuances.insert(tenant, new JourneyIssuance(journeyId, prepared.requestId(), issued.processId(), now, null));
        if (ticketNeeded && ticket == null) {
            return Optional.empty();
        }
        String onboardingUrl = ticket == null ? null : ticket.onboardingUrl();
        Instant onboardingExpiresAt = ticket == null ? null : ticket.expiresAt();
        IssuanceRecord section = readIssuance(tenant, journeyId).orElseGet(IssuanceRecord::empty);
        sessions.write(tenant, journeyId, Section.ISSUANCE, section.with(prepared.requestId(),
                new IssuanceRecord.Entry(prepared.idempotencyKey(), prepared.request().email(),
                        issued.credentialOfferUri(), issued.offerExpiresAt(), onboardingUrl, onboardingExpiresAt))
                .toJson());
        if (journey.isInProgress()) {
            journey.complete(now);
            if (!journeys.update(tenant, journey, JourneyState.IN_PROGRESS)) {
                throw new ProblemException(ProblemCode.JOURNEY_CLOSED);
            }
            audit(tenant, JourneyAuditTrail.Kind.COMPLETED, journey);
        }
        return Optional.of(new Offer(issued.credentialOfferUri(), issued.offerExpiresAt(), onboardingUrl,
                onboardingExpiresAt));
    }

    private Offer storedOffer(TenantId tenant, UUID journeyId, String requestId) {
        IssuanceRecord.Entry stored = readIssuance(tenant, journeyId).flatMap(section -> section.entry(requestId))
                .orElseThrow(() -> new ProblemException(ProblemCode.ONBOARDING_UNAVAILABLE));
        log.info("The request of journey {} was confirmed by a parallel confirmation: answering with its offer",
                journeyId);
        return new Offer(stored.offerUri().orElseThrow(() -> new ProblemException(ProblemCode.ONBOARDING_UNAVAILABLE)),
                stored.offerExpiresAt(), stored.onboarding().orElse(null), stored.onboardingExpiresAt());
    }

    private Journey lock(TenantId tenant, UUID journeyId) {
        return journeys.findByIdForUpdate(tenant, journeyId)
                .orElseThrow(() -> new ProblemException(ProblemCode.JOURNEY_CLOSED));
    }

    private HolderIdentity identity(TenantId tenant, UUID journeyId) {
        String section = sessions.read(tenant, journeyId, Section.IDENTITY)
                .orElseThrow(() -> new ProblemException(ProblemCode.STEP_NOT_ALLOWED));
        try {
            return HolderIdentity.fromJson(section);
        } catch (InvalidSectionException e) {
            log.warn("Identity of the journey session is invalid at {}", e.field());
            throw new ProblemException(ProblemCode.STEP_NOT_ALLOWED);
        }
    }

    private EntitlementRecord entitlement(TenantId tenant, UUID journeyId) {
        String section = sessions.read(tenant, journeyId, Section.ENTITLEMENT)
                .orElseThrow(() -> new ProblemException(ProblemCode.STEP_NOT_ALLOWED));
        try {
            return EntitlementRecord.fromJson(section);
        } catch (InvalidSectionException e) {
            log.warn("Entitlement of the journey session is invalid at {}", e.field());
            throw new ProblemException(ProblemCode.STEP_NOT_ALLOWED);
        }
    }

    /** A journey that already has an issuance section with something that is not one is not asked again blindly. */
    private Optional<IssuanceRecord> readIssuance(TenantId tenant, UUID journeyId) {
        return sessions.read(tenant, journeyId, Section.ISSUANCE).map(section -> {
            try {
                return IssuanceRecord.fromJson(section);
            } catch (InvalidSectionException e) {
                log.error("Issuance section of the journey session is invalid at {}", e.field());
                throw new ProblemException(ProblemCode.SERVICE_UNAVAILABLE);
            }
        });
    }

    /** ES-04: what the request needs from the tenant's configuration, checked before any system is asked. */
    private ServiceSettings settings(TenantId tenant) {
        ServiceSettings settings = configs.current(tenant).settings();
        boolean noAttestationType = settings.attestationType() == null || settings.attestationType().isBlank();
        boolean onboardingWithoutService = settings.onboarding().enabled()
                && (settings.onboarding().walletUnitServiceUrl() == null
                || settings.onboarding().walletUnitServiceUrl().isBlank());
        if (noAttestationType || settings.dataPlacement().isEmpty() || onboardingWithoutService) {
            log.warn("Portal configuration of tenant {} is incomplete for issuing", tenant);
            throw new ProblemException(ProblemCode.CONFIG_UNAVAILABLE);
        }
        return settings;
    }

    private Map<String, Object> payload(TenantId tenant, ServiceSettings settings, HolderIdentity identity,
                                        Map<String, Object> claims) {
        Map<String, String> fields = new HashMap<>();
        fields.put("given_name", identity.givenName());
        fields.put("family_name", identity.familyName());
        fields.put("personal_identifier", identity.personalIdentifier().value());
        identity.emailIfPresent().ifPresent(address -> fields.put("email", address));
        try {
            return IssuancePayload.build(settings.dataPlacement(), fields, claims);
        } catch (IncompletePlacementException e) {
            log.warn("Data placement of tenant {} for {} cannot fill {}", tenant, settings.attestationType(),
                    e.field());
            throw new ProblemException(ProblemCode.CONFIG_UNAVAILABLE);
        }
    }

    /** The trail never decides the outcome of a request: a failure is logged and the confirmation goes on. */
    private void trace(TenantId tenant, UUID journeyId, String requestId, JourneyAuditTrail.IssuanceOutcome outcome,
                       UUID processId) {
        try {
            trail.append(tenant, new JourneyAuditTrail.IssuanceRequested(UUID.randomUUID(), journeyId, requestId,
                    outcome, Optional.ofNullable(processId), clock.instant()));
        } catch (RuntimeException e) {
            log.error("Could not record the issuance request of journey {}", journeyId, e);
        }
    }

    private void traceTicket(TenantId tenant, UUID journeyId, JourneyAuditTrail.TicketOutcome outcome) {
        try {
            trail.append(tenant, new JourneyAuditTrail.OnboardingTicketRequested(UUID.randomUUID(), journeyId, outcome,
                    clock.instant()));
        } catch (RuntimeException e) {
            log.error("Could not record the onboarding ticket request of journey {}", journeyId, e);
        }
    }

    private void audit(TenantId tenant, JourneyAuditTrail.Kind kind, Journey journey) {
        try {
            trail.append(tenant, new JourneyAuditTrail.Event(UUID.randomUUID(), kind, journey.id(), journey.state(),
                    journey.step(), journey.withdrawalReason(), clock.instant()));
        } catch (RuntimeException e) {
            log.error("Could not record the {} event of journey {}", kind, journey.id(), e);
        }
    }
}
