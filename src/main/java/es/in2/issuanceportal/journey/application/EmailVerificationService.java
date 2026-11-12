package es.in2.issuanceportal.journey.application;

import es.in2.issuanceportal.journey.api.JourneyAccess;
import es.in2.issuanceportal.journey.application.port.EmailCodeDeliveryException;
import es.in2.issuanceportal.journey.application.port.EmailCodeMailer;
import es.in2.issuanceportal.journey.application.port.EmailCodeMessage;
import es.in2.issuanceportal.journey.application.port.EmailCodeRateLimit;
import es.in2.issuanceportal.journey.application.port.JourneyAuditTrail;
import es.in2.issuanceportal.journey.application.port.JourneyAuditTrail.EmailCodeCheck;
import es.in2.issuanceportal.journey.application.port.JourneyAuditTrail.EmailCodeOutcome;
import es.in2.issuanceportal.journey.application.port.JourneyRepository;
import es.in2.issuanceportal.journey.domain.EmailAddress;
import es.in2.issuanceportal.journey.domain.EmailLanguage;
import es.in2.issuanceportal.journey.domain.EmailVerification;
import es.in2.issuanceportal.journey.domain.InvalidEmailException;
import es.in2.issuanceportal.journey.domain.Journey;
import es.in2.issuanceportal.journey.domain.JourneyState;
import es.in2.issuanceportal.journey.domain.JourneyStep;
import es.in2.issuanceportal.session.api.EmailRecord;
import es.in2.issuanceportal.session.api.EmailVerificationRecord;
import es.in2.issuanceportal.session.api.InvalidSectionException;
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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Verifies the holder's email with a code before the confirmation (prd.md FR-22 to FR-24, design.md §3.6,
 * AD-13 to AD-15): the sending of the code and its check.
 *
 * <p>The journey's row stays locked from the checks to the write, the call to the mail provider included
 * (at most 5 seconds): two sends of the same journey at the same time cannot both pass the interval, and a
 * send that fails writes nothing, so the previous code stays as it was and the send counts for no limit.
 * The limit per client address is consumed last, only once nothing else refuses the send, and given back
 * when the email does not go out.
 *
 * <p>Neither the email, the code nor its hash reaches a log or the trail.
 */
@Slf4j
@Service
public class EmailVerificationService {

    static final String APP_NAME_FALLBACK_ES = "Portal de emisión";
    static final String APP_NAME_FALLBACK_EN = "Issuance portal";

    private final JourneyAccess access;
    private final JourneyRepository journeys;
    private final JourneySessions sessions;
    private final PortalConfigService configs;
    private final EmailCodeMailer mailer;
    private final EmailCodeRateLimit clientLimit;
    private final JourneyAuditTrail trail;
    private final TransactionOperations transactions;
    private final Clock clock;

    EmailVerificationService(JourneyAccess access, JourneyRepository journeys, JourneySessions sessions,
                             PortalConfigService configs, EmailCodeMailer mailer, EmailCodeRateLimit clientLimit,
                             JourneyAuditTrail trail, TransactionOperations transactions, Clock clock) {
        this.access = access;
        this.journeys = journeys;
        this.sessions = sessions;
        this.configs = configs;
        this.mailer = mailer;
        this.clientLimit = clientLimit;
        this.trail = trail;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Sends a new code to the email, which voids the previous one and stops the journey's email from being
     * verified until the new code is checked.
     *
     * @param rawEmail      what the interface sent as {@code email}, {@code null} when it sent none
     * @param clientAddress the client as the platform's proxies saw it, for the limit per address
     * @return the state of the verification after the send
     */
    public EmailVerification send(TenantId tenant, SessionId session, String rawEmail, EmailLanguage language,
                                  String clientAddress) {
        access.requireActive(tenant, session);
        UUID journeyId = sessions.journeyOf(tenant, session)
                .orElseThrow(() -> new ProblemException(ProblemCode.JOURNEY_CLOSED));
        return Objects.requireNonNull(transactions.execute(
                status -> sendLocked(tenant, session, journeyId, rawEmail, language, clientAddress)));
    }

    private EmailVerification sendLocked(TenantId tenant, SessionId session, UUID journeyId, String rawEmail,
                                         EmailLanguage language, String clientAddress) {
        requireOpenForCode(lock(tenant, journeyId));
        requireSection(tenant, session, Section.IDENTITY);
        requireSection(tenant, session, Section.ENTITLEMENT);
        EmailAddress email = email(rawEmail);
        Optional<EmailVerification> previous = readVerification(tenant, session);

        Optional<EmailVerification.Refusal> refusal = EmailVerification.refusal(previous.orElse(null), clock.instant());
        if (refusal.isPresent()) {
            throw refused(tenant, journeyId, refusal.get());
        }
        String portalName = portalName(tenant, language);
        Optional<Duration> wait = clientLimit.tryConsume(clientAddress);
        if (wait.isPresent()) {
            trace(tenant, journeyId, EmailCodeOutcome.TOO_MANY_REQUESTS);
            throw new ProblemException(ProblemCode.TOO_MANY_REQUESTS, wait.get());
        }

        String code = EmailVerification.newCode();
        try {
            mailer.send(EmailCodeMessage.of(email.value(), code, portalName, language,
                    tenant.value()));
        } catch (EmailCodeDeliveryException e) {
            clientLimit.giveBack(clientAddress);
            log.warn("Code email of journey {} not sent: {}", journeyId, e.getMessage());
            trace(tenant, journeyId, EmailCodeOutcome.EMAIL_UNAVAILABLE);
            throw new ProblemException(ProblemCode.EMAIL_UNAVAILABLE);
        } catch (RuntimeException e) {
            clientLimit.giveBack(clientAddress);
            throw e;
        }

        EmailVerification sent = EmailVerification.sent(email.value(), EmailVerification.hash(journeyId, code),
                previous.orElse(null), clock.instant());
        sessions.write(tenant, session, Section.EMAIL_VERIFICATION, toRecord(sent).toJson());
        // The email verified before stops being the journey's: the new one is only verified with its code (ac.md decision 2).
        sessions.clear(tenant, session, Section.EMAIL);
        trace(tenant, journeyId, EmailCodeOutcome.SENT);
        return sent;
    }

    /**
     * Checks the code the holder typed. The right code makes the pending email the journey's verified email, drops
     * the code and moves the journey to {@code email_verified}; a wrong one spends an attempt. The journey's row is
     * locked, so two checks at the same time cannot spend more than the 5 attempts (EC-03).
     *
     * <p>What a check changes is committed before it answers an error: the transaction returns its result and the
     * failure is raised outside it, otherwise the rollback would give the attempt back.
     *
     * @param code what the interface sent; anything that is not 6 digits is {@code bad_request} and spends nothing
     */
    public void check(TenantId tenant, SessionId session, String code) {
        if (!EmailVerification.isWellFormed(code)) {
            throw new ProblemException(ProblemCode.BAD_REQUEST);
        }
        access.requireActive(tenant, session);
        UUID journeyId = sessions.journeyOf(tenant, session)
                .orElseThrow(() -> new ProblemException(ProblemCode.JOURNEY_CLOSED));
        EmailVerification.Check.Outcome outcome = Objects.requireNonNull(
                transactions.execute(status -> checkLocked(tenant, session, journeyId, code)));
        traceCheck(tenant, journeyId, switch (outcome) {
            case VERIFIED -> EmailCodeCheck.VERIFIED;
            case INVALID_CODE -> EmailCodeCheck.INVALID_CODE;
            case EXPIRED_CODE -> EmailCodeCheck.EXPIRED_CODE;
            case TOO_MANY_ATTEMPTS -> EmailCodeCheck.TOO_MANY_ATTEMPTS;
        });
        switch (outcome) {
            case VERIFIED -> {
                // Nothing to refuse: the caller answers success.
            }
            case INVALID_CODE -> throw new ProblemException(ProblemCode.INVALID_CODE);
            case EXPIRED_CODE -> throw new ProblemException(ProblemCode.EXPIRED_CODE);
            case TOO_MANY_ATTEMPTS -> throw new ProblemException(ProblemCode.TOO_MANY_ATTEMPTS);
        }
    }

    private EmailVerification.Check.Outcome checkLocked(TenantId tenant, SessionId session, UUID journeyId,
                                                        String code) {
        Journey journey = lock(tenant, journeyId);
        requireOpenForCode(journey);
        // No code pending: never sent, or already verified (ES-03).
        EmailVerification pending = readVerification(tenant, session)
                .filter(EmailVerification::isPending)
                .orElseThrow(() -> new ProblemException(ProblemCode.STEP_NOT_ALLOWED));
        EmailVerification.Check check = pending.check(journeyId, code, clock.instant());
        switch (check.outcome()) {
            case VERIFIED -> {
                sessions.write(tenant, session, Section.EMAIL, new EmailRecord(pending.pendingEmail()).toJson());
                sessions.write(tenant, session, Section.EMAIL_VERIFICATION, toRecord(check.next()).toJson());
                reachEmailVerified(tenant, journey);
            }
            case INVALID_CODE ->
                    sessions.write(tenant, session, Section.EMAIL_VERIFICATION, toRecord(check.next()).toJson());
            case EXPIRED_CODE, TOO_MANY_ATTEMPTS -> {
                // Nothing to store: the stored record already says it is expired or exhausted.
            }
        }
        return check.outcome();
    }

    /**
     * The step is only ever reached, never left (ac.md decision 2): a new code later does not take it back, the
     * interface goes by whether the email is verified.
     */
    private void reachEmailVerified(TenantId tenant, Journey journey) {
        JourneyStep before = journey.step();
        journey.reach(JourneyStep.EMAIL_VERIFIED, clock.instant());
        if (!journeys.update(tenant, journey, JourneyState.IN_PROGRESS)) {
            throw new ProblemException(ProblemCode.JOURNEY_CLOSED);
        }
        if (journey.step() != before) {
            try {
                trail.append(tenant, new JourneyAuditTrail.Event(UUID.randomUUID(),
                        JourneyAuditTrail.Kind.STEP_REACHED, journey.id(), journey.state(), journey.step(),
                        journey.withdrawalReason(), clock.instant()));
            } catch (RuntimeException e) {
                log.error("Could not record the STEP_REACHED event of journey {}", journey.id(), e);
            }
        }
    }

    /**
     * A journey that already has its offer cannot send a code (EC-04) and answers {@code step_not_allowed}; one
     * that is withdrawn is closed. The pre-check of the caller did not look at it: it may have changed since.
     */
    private static void requireOpenForCode(Journey journey) {
        if (journey.state() == JourneyState.COMPLETED) {
            throw new ProblemException(ProblemCode.STEP_NOT_ALLOWED);
        }
        if (!journey.isInProgress()) {
            throw new ProblemException(ProblemCode.JOURNEY_CLOSED);
        }
    }

    private void requireSection(TenantId tenant, SessionId session, Section section) {
        if (sessions.read(tenant, session, section).isEmpty()) {
            throw new ProblemException(ProblemCode.STEP_NOT_ALLOWED);
        }
    }

    private static EmailAddress email(String raw) {
        try {
            return EmailAddress.parse(raw);
        } catch (InvalidEmailException e) {
            throw new ProblemException(e.reason() == InvalidEmailException.Reason.REQUIRED
                    ? ProblemCode.EMAIL_REQUIRED : ProblemCode.EMAIL_INVALID);
        }
    }

    private ProblemException refused(TenantId tenant, UUID journeyId, EmailVerification.Refusal refusal) {
        return switch (refusal.reason()) {
            case TOO_MANY_CODES -> {
                trace(tenant, journeyId, EmailCodeOutcome.TOO_MANY_CODES);
                yield new ProblemException(ProblemCode.TOO_MANY_CODES);
            }
            case RESEND_TOO_SOON -> {
                trace(tenant, journeyId, EmailCodeOutcome.RESEND_TOO_SOON);
                yield new ProblemException(ProblemCode.RESEND_TOO_SOON, refusal.retryAfter().orElseThrow());
            }
        };
    }

    /**
     * The portal's name in the language of the email, from {@code texts.<language>.portal.app_name} of the
     * tenant's configuration, or a default when it has none (EC-06).
     */
    private String portalName(TenantId tenant, EmailLanguage language) {
        Map<String, Object> texts = configs.current(tenant).texts();
        if (texts.get(language.code()) instanceof Map<?, ?> inLanguage
                && inLanguage.get("portal") instanceof Map<?, ?> portal
                && portal.get("app_name") instanceof String name && !name.isBlank()) {
            return name;
        }
        return language == EmailLanguage.EN ? APP_NAME_FALLBACK_EN : APP_NAME_FALLBACK_ES;
    }

    private Optional<EmailVerification> readVerification(TenantId tenant, SessionId session) {
        return sessions.read(tenant, session, Section.EMAIL_VERIFICATION).map(section -> {
            try {
                EmailVerificationRecord stored = EmailVerificationRecord.fromJson(section);
                return fromRecord(stored);
            } catch (InvalidSectionException e) {
                // Not read as empty: a journey with sends already counted must not get its limits back.
                log.error("Email verification section of the journey session is invalid at {}", e.field());
                throw new ProblemException(ProblemCode.SERVICE_UNAVAILABLE);
            }
        });
    }

    static EmailVerification fromRecord(EmailVerificationRecord stored) {
        return new EmailVerification(stored.pendingEmail(), stored.codeHash(), stored.codeExpiresAt(),
                stored.attempts(), stored.sends(), stored.lastSentAt(), stored.voidedCodeHashes());
    }

    private static EmailVerificationRecord toRecord(EmailVerification verification) {
        return new EmailVerificationRecord(verification.pendingEmail(), verification.codeHash(),
                verification.codeExpiresAt(), verification.attempts(), verification.sends(),
                verification.lastSentAt(), verification.voidedCodeHashes());
    }

    private Journey lock(TenantId tenant, UUID journeyId) {
        return journeys.findByIdForUpdate(tenant, journeyId)
                .orElseThrow(() -> new ProblemException(ProblemCode.JOURNEY_CLOSED));
    }

    private void traceCheck(TenantId tenant, UUID journeyId, EmailCodeCheck result) {
        try {
            trail.append(tenant, new JourneyAuditTrail.EmailCodeChecked(UUID.randomUUID(), journeyId, result,
                    clock.instant()));
        } catch (RuntimeException e) {
            log.error("Could not record the email code check of journey {}", journeyId, e);
        }
    }

    /** The trail never decides the outcome of a request: a failure is logged and the request goes on. */
    private void trace(TenantId tenant, UUID journeyId, EmailCodeOutcome outcome) {
        try {
            trail.append(tenant, new JourneyAuditTrail.EmailCodeRequested(UUID.randomUUID(), journeyId, outcome,
                    clock.instant()));
        } catch (RuntimeException e) {
            log.error("Could not record the email code request of journey {}", journeyId, e);
        }
    }
}
