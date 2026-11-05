package es.in2.issuanceportal.authenticsource.application;

import es.in2.issuanceportal.authenticsource.application.port.AuthenticSourceAuditTrail;
import es.in2.issuanceportal.authenticsource.domain.AttestationPreview;
import es.in2.issuanceportal.authenticsource.domain.AttestationTranslation;
import es.in2.issuanceportal.authenticsource.domain.EntitlementDecision;
import es.in2.issuanceportal.authenticsource.domain.EntitlementRules;
import es.in2.issuanceportal.authenticsource.domain.IdentifierType;
import es.in2.issuanceportal.authenticsource.domain.LookupResult;
import es.in2.issuanceportal.authenticsource.domain.NotConfirmedReason;
import es.in2.issuanceportal.authenticsource.domain.PersonalIdentifier;
import es.in2.issuanceportal.authenticsource.domain.SourceRecord;
import es.in2.issuanceportal.authenticsource.domain.config.SourceSettings;
import es.in2.issuanceportal.journey.api.JourneyAccess;
import es.in2.issuanceportal.journey.api.JourneyProgress;
import es.in2.issuanceportal.journey.api.JourneyWithdrawal;
import es.in2.issuanceportal.session.api.EntitlementRecord;
import es.in2.issuanceportal.session.api.HolderIdentity;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Checks the holder's entitlement in the tenant's authentic source (design.md §3.3), with the personal
 * identifier and its type taken only from the journey's identity, never from the request.
 *
 * <p>No transaction is held while the source is asked: a slow source must not keep a database
 * connection. What a confirmed check writes (the entitlement and the step) commits together.
 */
@Slf4j
@Service
public class EntitlementService implements AutoCloseable {

    /** How long the interface waits before retrying an unavailable source (openapi 503). */
    static final Duration RETRY_AFTER = Duration.ofSeconds(30);

    private final JourneyAccess access;
    private final JourneyProgress progress;
    private final JourneyWithdrawal withdrawal;
    private final JourneySessions sessions;
    private final AuthenticSourceConfigs configs;
    private final AuthenticSourceAuditTrail trail;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final ExecutorService lookups = Executors.newVirtualThreadPerTaskExecutor();
    private final LookupSlots lookupSlots;

    EntitlementService(JourneyAccess access, JourneyProgress progress, JourneyWithdrawal withdrawal,
                       JourneySessions sessions, AuthenticSourceConfigs configs, AuthenticSourceAuditTrail trail,
                       TransactionOperations transactions, Clock clock, LookupSlots lookupSlots) {
        this.access = access;
        this.progress = progress;
        this.withdrawal = withdrawal;
        this.sessions = sessions;
        this.configs = configs;
        this.trail = trail;
        this.transactions = transactions;
        this.clock = clock;
        this.lookupSlots = lookupSlots;
    }

    /** The result the interface gets: confirmed with what the Data step shows, or not confirmed. */
    public sealed interface Outcome {

        record Confirmed(AttestationPreview preview) implements Outcome {
        }

        record NotConfirmed(NotConfirmedReason reason) implements Outcome {
        }
    }

    public Outcome check(TenantId tenant, SessionId session) {
        long started = System.nanoTime();
        access.requireInProgress(tenant, session);
        // Read before anything else: a withdrawal deletes the session, and the trace still needs the journey.
        UUID journeyId = sessions.journeyOf(tenant, session)
                .orElseThrow(() -> new ProblemException(ProblemCode.JOURNEY_CLOSED));
        HolderIdentity identity = identity(tenant, session);
        PersonalIdentifier searched = toDomain(identity);
        AuthenticSourceConfigs.Current current = configs.current(tenant);
        Trace trace = new Trace(tenant, journeyId, current, searched.type(), started);
        if (!current.hasSource()) {
            // Allowed by FR-06, but it also happens when the source was left out by mistake: make it visible.
            log.warn("Entitlement of tenant {} confirmed for {} without an authentic source: identity only",
                    tenant, current.attestationType());
            AttestationPreview preview = AttestationPreview.of(current.attestationType(), identity.givenName(),
                    identity.familyName(), List.of(), Map.of(), identity.email(), null);
            trace.write(AuthenticSourceAuditTrail.Result.CONFIRMED, null);
            confirm(tenant, session, null, Map.of(), preview);
            return new Outcome.Confirmed(preview);
        }
        if (!current.identifierTypes().contains(searched.type())) {
            // The configuration checks cover the login's types; an identity outside them is a tenant setup error.
            log.warn("Authentic source configuration of tenant {} for {} cannot search by {}", tenant,
                    current.attestationType(), searched.type().code());
            throw new ProblemException(ProblemCode.CONFIG_UNAVAILABLE);
        }
        LookupResult answer;
        try {
            answer = lookup(tenant, current, searched);
        } catch (ProblemException unavailable) {
            trace.write(AuthenticSourceAuditTrail.Result.UNAVAILABLE, null);
            throw unavailable;
        }
        // The trace says what the source answered before acting on it, so a failure afterwards keeps it.
        return switch (EntitlementRules.decide(searched, answer)) {
            case EntitlementDecision.Confirmed(SourceRecord found) -> {
                trace.write(AuthenticSourceAuditTrail.Result.CONFIRMED, null);
                yield confirmed(tenant, session, identity, current, found);
            }
            case EntitlementDecision.NotConfirmed(NotConfirmedReason reason) -> {
                trace.write(AuthenticSourceAuditTrail.Result.NOT_CONFIRMED, reason);
                withdrawal.withdraw(tenant, session, JourneyWithdrawal.Cause.NOT_ENTITLED);
                yield new Outcome.NotConfirmed(reason);
            }
        };
    }

    /** One check's trace: what it was, so each outcome only adds its result. */
    private final class Trace {

        private final TenantId tenant;
        private final UUID journeyId;
        private final AuthenticSourceConfigs.Current current;
        private final IdentifierType identifierType;
        private final long started;

        Trace(TenantId tenant, UUID journeyId, AuthenticSourceConfigs.Current current, IdentifierType identifierType,
              long started) {
            this.tenant = tenant;
            this.journeyId = journeyId;
            this.current = current;
            this.identifierType = identifierType;
            this.started = started;
        }

        /** The trail never decides the outcome of a check: a failure is logged and the check goes on. */
        void write(AuthenticSourceAuditTrail.Result result, NotConfirmedReason reason) {
            try {
                trail.append(tenant, new AuthenticSourceAuditTrail.Event(UUID.randomUUID(), journeyId,
                        current.attestationType(),
                        current.hasSource() ? current.adapter().type() : AuthenticSourceAuditTrail.NO_ADAPTER,
                        identifierType, result, Optional.ofNullable(reason),
                        Duration.ofNanos(System.nanoTime() - started), clock.instant()));
            } catch (RuntimeException e) {
                log.error("Could not record the entitlement check of journey {}", journeyId, e);
            }
        }
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

    /**
     * Asks the source on a virtual thread and waits at most the configured timeout. Any failure, including
     * an adapter that does not answer, is an unavailable source: never a confirmed one (FR-05).
     */
    private LookupResult lookup(TenantId tenant, AuthenticSourceConfigs.Current current, PersonalIdentifier searched) {
        Duration timeout = current.settings().timeout();
        // A slow source must not take every thread of the service, nor the slots of other tenants, with it.
        LookupSlots.Slot slot = lookupSlots.tryAcquire(tenant).orElseThrow(() -> {
            log.warn("Authentic source {} of tenant {} not asked: too many searches in flight",
                    current.adapter().type(), tenant);
            return unavailable();
        });
        Future<LookupResult> answer;
        try {
            answer = lookups.submit(() -> {
                if (!slot.start()) {
                    // Cancelled before it started: whoever stopped waiting already gave the slot back.
                    return null;
                }
                try {
                    return current.adapter().lookup(tenant, current.attestationType(), searched, timeout);
                } finally {
                    slot.release();
                }
            });
        } catch (RuntimeException _) {
            slot.release();
            throw unavailable();
        }
        try {
            return Objects.requireNonNull(answer.get(timeout.toMillis(), TimeUnit.MILLISECONDS),
                    "lookup answered nothing");
        } catch (TimeoutException _) {
            answer.cancel(true);
            log.warn("Authentic source {} of tenant {} did not answer within {}", current.adapter().type(), tenant,
                    timeout);
            throw unavailable();
        } catch (ExecutionException | RuntimeException e) {
            log.warn("Authentic source {} of tenant {} failed: {}", current.adapter().type(), tenant,
                    kind(e));
            throw unavailable();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            answer.cancel(true);
            throw unavailable();
        } finally {
            // A search cancelled before it started never runs its own release.
            slot.abandon();
        }
    }

    private Outcome confirmed(TenantId tenant, SessionId session, HolderIdentity identity,
                              AuthenticSourceConfigs.Current current, SourceRecord found) {
        SourceSettings settings = current.settings();
        Map<String, Object> claims = AttestationTranslation.translate(settings.mapping(), found);
        Object sourceEmail = settings.emailAttribute() == null ? null : found.attributes().get(settings.emailAttribute());
        AttestationPreview preview = AttestationPreview.of(settings.typeName(), identity.givenName(),
                identity.familyName(), settings.mapping(), claims, identity.email(), sourceEmail);
        confirm(tenant, session, identity.personalIdentifier().type(), claims, preview);
        return new Outcome.Confirmed(preview);
    }

    /**
     * The entitlement and the step commit together: a journey never reaches the step without its data.
     *
     * @param searchedBy the identity's identifier type, {@code null} when the attestation type has no source
     */
    private void confirm(TenantId tenant, SessionId session,
                         es.in2.issuanceportal.session.api.IdentifierType searchedBy,
                         Map<String, Object> claims, AttestationPreview preview) {
        EntitlementRecord entitlement = new EntitlementRecord(searchedBy, claims, toSection(preview), clock.instant());
        transactions.executeWithoutResult(status -> {
            sessions.write(tenant, session, Section.ENTITLEMENT, entitlement.toJson());
            progress.reach(tenant, session, JourneyProgress.Step.ENTITLEMENT_CONFIRMED);
        });
    }

    private static EntitlementRecord.Preview toSection(AttestationPreview preview) {
        return new EntitlementRecord.Preview(preview.typeName(),
                preview.fields().stream().map(field -> new EntitlementRecord.Field(field.label(), field.value()))
                        .toList(),
                preview.email(), preview.emailRequired());
    }

    private static PersonalIdentifier toDomain(HolderIdentity identity) {
        IdentifierType type = switch (identity.personalIdentifier().type()) {
            case DNI -> IdentifierType.DNI;
            case COLEGIADO -> IdentifierType.COLEGIADO;
        };
        return new PersonalIdentifier(type, identity.personalIdentifier().value());
    }

    private static ProblemException unavailable() {
        return new ProblemException(ProblemCode.AUTHENTIC_SOURCE_UNAVAILABLE, RETRY_AFTER);
    }

    /** The kind of failure only: an adapter's message may quote what it sent or received. */
    private static String kind(Exception e) {
        Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
        return cause.getClass().getSimpleName();
    }

    @Override
    public void close() {
        lookups.shutdownNow();
    }
}
