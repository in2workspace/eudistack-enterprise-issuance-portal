package es.in2.issuanceportal.journey.application;

import es.in2.issuanceportal.journey.api.JourneyAccess;
import es.in2.issuanceportal.journey.application.port.EmailCodeDeliveryException;
import es.in2.issuanceportal.journey.application.port.EmailCodeMailer;
import es.in2.issuanceportal.journey.application.port.EmailCodeMessage;
import es.in2.issuanceportal.journey.application.port.EmailCodeRateLimit;
import es.in2.issuanceportal.journey.application.port.JourneyAuditTrail;
import es.in2.issuanceportal.journey.application.port.JourneyAuditTrail.EmailCodeOutcome;
import es.in2.issuanceportal.journey.application.port.JourneyRepository;
import es.in2.issuanceportal.journey.domain.EmailAddress;
import es.in2.issuanceportal.journey.domain.EmailLanguage;
import es.in2.issuanceportal.journey.domain.EmailVerification;
import es.in2.issuanceportal.journey.domain.InvalidEmailException;
import es.in2.issuanceportal.journey.domain.Journey;
import es.in2.issuanceportal.journey.domain.JourneyState;
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
 * AD-13 to AD-15). This is the sending of the code.
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
            mailer.send(EmailCodeMessage.of(email.value(), code, portalName, language));
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
                return new EmailVerification(stored.pendingEmail(), stored.codeHash(), stored.codeExpiresAt(),
                        stored.attempts(), stored.sends(), stored.lastSentAt());
            } catch (InvalidSectionException e) {
                // Not read as empty: a journey with sends already counted must not get its limits back.
                log.error("Email verification section of the journey session is invalid at {}", e.field());
                throw new ProblemException(ProblemCode.SERVICE_UNAVAILABLE);
            }
        });
    }

    private static EmailVerificationRecord toRecord(EmailVerification verification) {
        return new EmailVerificationRecord(verification.pendingEmail(), verification.codeHash(),
                verification.codeExpiresAt(), verification.attempts(), verification.sends(),
                verification.lastSentAt());
    }

    private Journey lock(TenantId tenant, UUID journeyId) {
        return journeys.findByIdForUpdate(tenant, journeyId)
                .orElseThrow(() -> new ProblemException(ProblemCode.JOURNEY_CLOSED));
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
