package es.in2.issuanceportal.journey.application;

import es.in2.issuanceportal.journey.api.JourneyAccess;
import es.in2.issuanceportal.journey.application.port.IssuanceApi;
import es.in2.issuanceportal.journey.application.port.IssuanceRejectedException;
import es.in2.issuanceportal.journey.application.port.IssuanceRequest;
import es.in2.issuanceportal.journey.application.port.IssuanceResult;
import es.in2.issuanceportal.journey.application.port.IssuanceUnavailableException;
import es.in2.issuanceportal.journey.application.port.JourneyAuditTrail;
import es.in2.issuanceportal.journey.application.port.JourneyRepository;
import es.in2.issuanceportal.journey.application.port.OnboardingTicket;
import es.in2.issuanceportal.journey.application.port.OnboardingTicketException;
import es.in2.issuanceportal.journey.application.port.OnboardingTickets;
import es.in2.issuanceportal.journey.application.port.SystemTokenUnavailableException;
import es.in2.issuanceportal.journey.domain.EmailAddress;
import es.in2.issuanceportal.journey.domain.IncompletePlacementException;
import es.in2.issuanceportal.journey.domain.InvalidEmailException;
import es.in2.issuanceportal.journey.domain.IssuanceProcessAlreadyRecordedException;
import es.in2.issuanceportal.journey.domain.IssuancePayload;
import es.in2.issuanceportal.journey.domain.Journey;
import es.in2.issuanceportal.journey.domain.JourneyState;
import es.in2.issuanceportal.journey.domain.config.Onboarding;
import es.in2.issuanceportal.journey.domain.config.ServiceSettings;
import es.in2.issuanceportal.session.api.EmailRecord;
import es.in2.issuanceportal.session.api.EntitlementRecord;
import es.in2.issuanceportal.session.api.HolderIdentity;
import es.in2.issuanceportal.session.api.InvalidSectionException;
import es.in2.issuanceportal.session.api.IssuanceRecord;
import es.in2.issuanceportal.session.api.JourneySessions;
import es.in2.issuanceportal.session.api.Section;
import es.in2.issuanceportal.session.api.SessionId;
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
 * The holder's confirmation: requests the issuance as a system and hands the offer back (design.md §3.3,
 * AD-12). A journey asks the Issuer once, with one idempotency key generated the first time and kept in the
 * journey for every retry (EC-02), so a journey ends with at most one issuance process.
 *
 * <p>The journey's row is locked in two short transactions and never while the Issuer is asked: the first
 * checks the state and keeps the key, the second keeps the process and the offer and completes the
 * journey. No database connection waits for an external system. Two confirmations at the same time share
 * the key the first one stored (EC-03); the Issuer's idempotency gives both the same process, and the one
 * that finishes second answers with the offer the first stored.
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
    private final JourneySessions sessions;
    private final PortalConfigService configs;
    private final IssuanceApi issuance;
    private final OnboardingTickets tickets;
    private final JourneyAuditTrail trail;
    private final TransactionOperations transactions;
    private final Clock clock;

    IssuanceService(JourneyAccess access, JourneyRepository journeys, JourneySessions sessions,
                    PortalConfigService configs, IssuanceApi issuance, OnboardingTickets tickets,
                    JourneyAuditTrail trail, TransactionOperations transactions, Clock clock) {
        this.access = access;
        this.journeys = journeys;
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

    /** What the first transaction leaves ready for the request. */
    private record Prepared(UUID idempotencyKey, IssuanceRequest request, Onboarding onboarding) {
    }

    /**
     * @param rawEmail what the interface sent as {@code email}, {@code null} when it sent none
     */
    public Offer confirm(TenantId tenant, SessionId session, String rawEmail) {
        access.requireInProgress(tenant, session);
        UUID journeyId = sessions.journeyOf(tenant, session)
                .orElseThrow(() -> new ProblemException(ProblemCode.JOURNEY_CLOSED));
        EmailAddress email = email(rawEmail);
        Prepared prepared = Objects.requireNonNull(
                transactions.execute(status -> prepare(tenant, session, journeyId, email)));
        IssuanceResult issued = request(tenant, journeyId, prepared);
        boolean ticketNeeded = prepared.onboarding().enabled();
        OnboardingTicket ticket = ticketNeeded ? requestTicket(tenant, journeyId, prepared, issued) : null;
        trace(tenant, journeyId, ticketNeeded && ticket == null ? JourneyAuditTrail.IssuanceOutcome.ONBOARDING_FAILED
                : JourneyAuditTrail.IssuanceOutcome.ISSUED, issued.processId());
        Optional<Offer> offer = Objects.requireNonNull(transactions.execute(status -> finish(tenant, session,
                journeyId, prepared.idempotencyKey(), issued, ticketNeeded, ticket)));
        // The process is kept, the offer is not given: without the ticket the holder could not use it in the wallet.
        return offer.orElseThrow(() -> new ProblemException(ProblemCode.ONBOARDING_UNAVAILABLE));
    }

    private static EmailAddress email(String raw) {
        try {
            return EmailAddress.parse(raw);
        } catch (InvalidEmailException e) {
            throw new ProblemException(e.reason() == InvalidEmailException.Reason.REQUIRED
                    ? ProblemCode.EMAIL_REQUIRED : ProblemCode.EMAIL_INVALID);
        }
    }

    /**
     * First transaction: with the journey locked, checks what the request needs, keeps the email and, only
     * if the journey has none yet, a new idempotency key. Anything that makes it fail leaves the journey as
     * it was.
     */
    private Prepared prepare(TenantId tenant, SessionId session, UUID journeyId, EmailAddress email) {
        Journey journey = lock(tenant, journeyId);
        if (!journey.isInProgress()) {
            throw new ProblemException(ProblemCode.JOURNEY_CLOSED);
        }
        HolderIdentity identity = identity(tenant, session);
        EntitlementRecord entitlement = entitlement(tenant, session);
        ServiceSettings settings = settings(tenant);
        if (journey.issuanceProcessId().isPresent()) {
            // The Issuer has no way yet to give another offer for the process (EC-04); the renewal comes with EUD-295.
            log.info("Journey {} already has its issuance process: not requested again", journeyId);
            throw new ProblemException(ProblemCode.ONBOARDING_UNAVAILABLE);
        }
        Map<String, Object> payload = payload(tenant, settings, identity, entitlement);
        Optional<IssuanceRecord> issued = readIssuance(tenant, session);
        UUID key;
        String address;
        if (issued.isPresent()) {
            // The key and the email it was first used with stay together: the Issuer's idempotency is on the
            // key, so a later body with another email must not reach it. The new email was only validated.
            key = issued.get().idempotencyKey();
            address = storedEmail(tenant, session).orElse(email.value());
        } else {
            key = UUID.randomUUID();
            address = email.value();
            sessions.write(tenant, session, Section.ISSUANCE, IssuanceRecord.withKey(key).toJson());
        }
        if (issued.isEmpty() || storedEmail(tenant, session).isEmpty()) {
            sessions.write(tenant, session, Section.EMAIL, new EmailRecord(address).toJson());
        }
        return new Prepared(key, new IssuanceRequest(settings.attestationType(), payload, address,
                DELIVERY, GRANT_TYPE), settings.onboarding());
    }

    private Optional<String> storedEmail(TenantId tenant, SessionId session) {
        return sessions.read(tenant, session, Section.EMAIL).flatMap(section -> {
            try {
                return Optional.of(EmailRecord.fromJson(section).address());
            } catch (InvalidSectionException e) {
                log.warn("Email of the journey session is invalid at {}", e.field());
                return Optional.empty();
            }
        });
    }

    /** Outside any transaction. Every failure leaves the journey in progress, with its key, for the next attempt. */
    private IssuanceResult request(TenantId tenant, UUID journeyId, Prepared prepared) {
        try {
            return issuance.issue(tenant, prepared.idempotencyKey(), prepared.request());
        } catch (SystemTokenUnavailableException e) {
            log.warn("No system token of tenant {} to issue for journey {}: {}", tenant, journeyId, e.reason());
            trace(tenant, journeyId, JourneyAuditTrail.IssuanceOutcome.SYSTEM_TOKEN_UNAVAILABLE, null);
            throw new ProblemException(ProblemCode.ISSUANCE_UNAVAILABLE);
        } catch (IssuanceRejectedException e) {
            log.warn("Issuance of journey {} rejected: {}", journeyId, e.reason());
            trace(tenant, journeyId, JourneyAuditTrail.IssuanceOutcome.REJECTED, null);
            throw new ProblemException(ProblemCode.ISSUANCE_REJECTED);
        } catch (IssuanceUnavailableException e) {
            log.warn("Issuance of journey {} unavailable: {}", journeyId, e.reason());
            trace(tenant, journeyId, JourneyAuditTrail.IssuanceOutcome.UNAVAILABLE, null);
            throw new ProblemException(ProblemCode.ISSUANCE_UNAVAILABLE);
        }
    }

    /**
     * Outside any transaction. Any failure, or an offer with too little life left for a ticket that expires
     * before it, gives no ticket: the process the Issuer created stays for the next attempt.
     *
     * @return the ticket, or {@code null} when it was not created
     */
    private OnboardingTicket requestTicket(TenantId tenant, UUID journeyId, Prepared prepared, IssuanceResult issued) {
        Duration expiresIn = Duration.between(clock.instant(), issued.offerExpiresAt()).minus(TICKET_MARGIN);
        if (expiresIn.compareTo(Duration.ofSeconds(1)) < 0) {
            log.warn("Offer of journey {} has too little life left for an onboarding ticket: not requested", journeyId);
            traceTicket(tenant, journeyId, JourneyAuditTrail.TicketOutcome.FAILED);
            return null;
        }
        try {
            OnboardingTicket ticket = tickets.request(tenant, prepared.onboarding().walletUnitServiceUrl(),
                    prepared.request().email(), issued.credentialOfferUri(), expiresIn);
            traceTicket(tenant, journeyId, JourneyAuditTrail.TicketOutcome.CREATED);
            return ticket;
        } catch (OnboardingTicketException e) {
            log.warn("Onboarding ticket of journey {} not created: {}", journeyId, e.reason());
            traceTicket(tenant, journeyId, JourneyAuditTrail.TicketOutcome.FAILED);
            return null;
        }
    }

    /**
     * Second transaction. With the ticket, or in a tenant without onboarding, keeps the process and the offer
     * (and the onboarding address) and completes the journey. Without a ticket that was needed, keeps only the
     * process: no offer and no address, and the journey stays in progress. If a parallel confirmation of the
     * same journey completed it first, answers with the offer that one stored.
     *
     * @param ticketNeeded whether the tenant uses onboarding tickets
     * @param ticket       {@code null} when the tenant has no onboarding or the ticket was not created
     * @return the offer to give, or empty when the ticket was needed and not created
     */
    private Optional<Offer> finish(TenantId tenant, SessionId session, UUID journeyId, UUID key,
                                   IssuanceResult issued, boolean ticketNeeded, OnboardingTicket ticket) {
        Journey journey = lock(tenant, journeyId);
        if (journey.state() == JourneyState.COMPLETED) {
            return Optional.of(storedOffer(tenant, session, journeyId));
        }
        if (!journey.isInProgress()) {
            // Withdrawn or expired while the Issuer was asked. The process it created stays unused.
            log.warn("Journey {} closed while its issuance was requested", journeyId);
            throw new ProblemException(ProblemCode.JOURNEY_CLOSED);
        }
        Instant now = clock.instant();
        try {
            journey.recordIssuanceProcess(issued.processId(), now);
        } catch (IssuanceProcessAlreadyRecordedException _) {
            // The Issuer gave another process for a journey that already has one: it keeps the one it has.
            log.warn("Journey {} already had a different issuance process: the new one is not recorded", journeyId);
            throw new ProblemException(ProblemCode.ONBOARDING_UNAVAILABLE);
        }
        // On its own and conditional: the generic update of the journey does not write the process (NFR-2).
        if (!journeys.recordIssuanceProcess(tenant, journeyId, issued.processId())) {
            log.warn("Journey {} already had a different issuance process: the new one is not recorded", journeyId);
            throw new ProblemException(ProblemCode.ONBOARDING_UNAVAILABLE);
        }
        if (ticketNeeded && ticket == null) {
            if (!journeys.update(tenant, journey, JourneyState.IN_PROGRESS)) {
                throw new ProblemException(ProblemCode.JOURNEY_CLOSED);
            }
            return Optional.empty();
        }
        String onboardingUrl = ticket == null ? null : ticket.onboardingUrl();
        Instant onboardingExpiresAt = ticket == null ? null : ticket.expiresAt();
        sessions.write(tenant, session, Section.ISSUANCE, new IssuanceRecord(key, issued.credentialOfferUri(),
                issued.offerExpiresAt(), onboardingUrl, onboardingExpiresAt).toJson());
        journey.complete(now);
        if (!journeys.update(tenant, journey, JourneyState.IN_PROGRESS)) {
            throw new ProblemException(ProblemCode.JOURNEY_CLOSED);
        }
        audit(tenant, JourneyAuditTrail.Kind.COMPLETED, journey);
        return Optional.of(new Offer(issued.credentialOfferUri(), issued.offerExpiresAt(), onboardingUrl,
                onboardingExpiresAt));
    }

    private Offer storedOffer(TenantId tenant, SessionId session, UUID journeyId) {
        IssuanceRecord stored = readIssuance(tenant, session)
                .orElseThrow(() -> new ProblemException(ProblemCode.JOURNEY_CLOSED));
        log.info("Journey {} was completed by a parallel confirmation: answering with its offer", journeyId);
        return new Offer(stored.offerUri().orElseThrow(() -> new ProblemException(ProblemCode.JOURNEY_CLOSED)),
                stored.offerExpiresAt(), stored.onboarding().orElse(null), stored.onboardingExpiresAt());
    }

    private Journey lock(TenantId tenant, UUID journeyId) {
        return journeys.findByIdForUpdate(tenant, journeyId)
                .orElseThrow(() -> new ProblemException(ProblemCode.JOURNEY_CLOSED));
    }

    private HolderIdentity identity(TenantId tenant, SessionId session) {
        String section = sessions.read(tenant, session, Section.IDENTITY)
                .orElseThrow(() -> new ProblemException(ProblemCode.STEP_NOT_ALLOWED));
        try {
            return HolderIdentity.fromJson(section);
        } catch (InvalidSectionException e) {
            log.warn("Identity of the journey session is invalid at {}", e.field());
            throw new ProblemException(ProblemCode.STEP_NOT_ALLOWED);
        }
    }

    private EntitlementRecord entitlement(TenantId tenant, SessionId session) {
        String section = sessions.read(tenant, session, Section.ENTITLEMENT)
                .orElseThrow(() -> new ProblemException(ProblemCode.STEP_NOT_ALLOWED));
        try {
            return EntitlementRecord.fromJson(section);
        } catch (InvalidSectionException e) {
            log.warn("Entitlement of the journey session is invalid at {}", e.field());
            throw new ProblemException(ProblemCode.STEP_NOT_ALLOWED);
        }
    }

    /** A journey that already has an issuance section with something that is not one is not asked again blindly. */
    private Optional<IssuanceRecord> readIssuance(TenantId tenant, SessionId session) {
        return sessions.read(tenant, session, Section.ISSUANCE).map(section -> {
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
                                        EntitlementRecord entitlement) {
        Map<String, String> fields = new HashMap<>();
        fields.put("given_name", identity.givenName());
        fields.put("family_name", identity.familyName());
        fields.put("personal_identifier", identity.personalIdentifier().value());
        identity.emailIfPresent().ifPresent(address -> fields.put("email", address));
        try {
            return IssuancePayload.build(settings.dataPlacement(), fields, entitlement.claims());
        } catch (IncompletePlacementException e) {
            log.warn("Data placement of tenant {} for {} cannot fill {}", tenant, settings.attestationType(),
                    e.field());
            throw new ProblemException(ProblemCode.CONFIG_UNAVAILABLE);
        }
    }

    /** The trail never decides the outcome of a request: a failure is logged and the confirmation goes on. */
    private void trace(TenantId tenant, UUID journeyId, JourneyAuditTrail.IssuanceOutcome outcome, UUID processId) {
        try {
            trail.append(tenant, new JourneyAuditTrail.IssuanceRequested(UUID.randomUUID(), journeyId, outcome,
                    Optional.ofNullable(processId), clock.instant()));
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
