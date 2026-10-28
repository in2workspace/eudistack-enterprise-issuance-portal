package es.in2.issuanceportal.authenticsource.application;

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
    private final TransactionOperations transactions;
    private final Clock clock;
    private final ExecutorService lookups = Executors.newVirtualThreadPerTaskExecutor();

    public EntitlementService(JourneyAccess access, JourneyProgress progress, JourneyWithdrawal withdrawal,
                              JourneySessions sessions, AuthenticSourceConfigs configs,
                              TransactionOperations transactions, Clock clock) {
        this.access = access;
        this.progress = progress;
        this.withdrawal = withdrawal;
        this.sessions = sessions;
        this.configs = configs;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** The result the interface gets: confirmed with what the Data step shows, or not confirmed. */
    public sealed interface Outcome {

        record Confirmed(AttestationPreview preview) implements Outcome {
        }

        record NotConfirmed(NotConfirmedReason reason) implements Outcome {
        }
    }

    public Outcome check(TenantId tenant, SessionId session) {
        access.requireInProgress(tenant, session);
        HolderIdentity identity = identity(tenant, session);
        AuthenticSourceConfigs.Current current = configs.current(tenant);
        if (!current.hasSource()) {
            AttestationPreview preview = AttestationPreview.of(current.attestationType(), identity.givenName(),
                    identity.familyName(), List.of(), Map.of(), identity.email(), null);
            confirm(tenant, session, null, Map.of(), preview);
            return new Outcome.Confirmed(preview);
        }
        PersonalIdentifier searched = toDomain(identity);
        LookupResult answer = lookup(tenant, current, searched);
        return switch (EntitlementRules.decide(searched, answer)) {
            case EntitlementDecision.Confirmed(SourceRecord found) -> confirmed(tenant, session, identity, current, found);
            case EntitlementDecision.NotConfirmed(NotConfirmedReason reason) -> {
                withdrawal.withdraw(tenant, session, JourneyWithdrawal.Cause.NOT_ENTITLED);
                yield new Outcome.NotConfirmed(reason);
            }
        };
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
        Future<LookupResult> answer = lookups.submit(() -> current.adapter()
                .lookup(tenant, current.attestationType(), searched, timeout));
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
