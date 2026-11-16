package es.in2.issuanceportal.authenticsource.application;

import es.in2.issuanceportal.authenticsource.application.port.AuthenticSourceAuditTrail;
import es.in2.issuanceportal.authenticsource.domain.AttestationPreview;
import es.in2.issuanceportal.authenticsource.domain.AttestationTranslation;
import es.in2.issuanceportal.authenticsource.domain.EntitlementDecision;
import es.in2.issuanceportal.authenticsource.domain.EntitlementRules;
import es.in2.issuanceportal.authenticsource.domain.Holder;
import es.in2.issuanceportal.authenticsource.domain.IdentifierType;
import es.in2.issuanceportal.authenticsource.domain.MismatchedField;
import es.in2.issuanceportal.authenticsource.domain.NotConfirmedReason;
import es.in2.issuanceportal.authenticsource.domain.PersonalIdentifier;
import es.in2.issuanceportal.authenticsource.domain.RequestStatus;
import es.in2.issuanceportal.authenticsource.domain.SourceRequest;
import es.in2.issuanceportal.authenticsource.domain.SourceRequests;
import es.in2.issuanceportal.authenticsource.domain.config.SourceSettings;
import es.in2.issuanceportal.journey.api.JourneyAccess;
import es.in2.issuanceportal.journey.api.JourneyProgress;
import es.in2.issuanceportal.journey.api.JourneyWithdrawal;
import es.in2.issuanceportal.session.api.EntitlementRecord;
import es.in2.issuanceportal.session.api.HolderIdentity;
import es.in2.issuanceportal.session.api.HolderRef;
import es.in2.issuanceportal.session.api.InvalidSectionException;
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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Checks the holder's requests in the tenant's authentic source (design.md §3.3), with the name, the family
 * names and the personal identifier with its type taken only from the journey's identity, never from the
 * request.
 *
 * <p>No transaction is held while the source is asked: a slow source must not keep a database
 * connection. What a confirmed check writes (the entitlement and the step) commits together.
 */
@Slf4j
@Service
public class EntitlementService implements AutoCloseable {

    /** How long the interface waits before retrying an unavailable source (openapi 503). */
    static final Duration RETRY_AFTER = Duration.ofSeconds(30);
    /** The reference of the only request of an attestation type without a source: it has none of its own. */
    static final String NO_SOURCE_REF = "no-source";
    private static final String ID_PREFIX = "r";

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

    /** The result the interface gets: the holder's requests, confirmed when at least one is available. */
    public sealed interface Outcome {

        /** In the order the source gave them; empty when {@code NotConfirmed} has a reason. */
        List<CheckedRequest> requests();

        record Confirmed(List<CheckedRequest> requests) implements Outcome {
            public Confirmed {
                requests = List.copyOf(requests);
            }
        }

        /** {@code reason} is empty when the holder has requests and none is available. */
        record NotConfirmed(Optional<NotConfirmedReason> reason, List<CheckedRequest> requests) implements Outcome {
            public NotConfirmed {
                requests = List.copyOf(requests);
            }
        }
    }

    /**
     * One request as the interface sees it: with the id the module assigned and never the source's
     * reference. {@code preview} is there only for an available or under review request. Its text form
     * carries no personal data.
     */
    public record CheckedRequest(String id, RequestStatus status, List<MismatchedField> mismatchedFields,
                                 boolean renewal, String requestedBy, Instant requestedAt, String displayIdentifier,
                                 String organization, Optional<AttestationPreview> preview) {

        public CheckedRequest {
            mismatchedFields = List.copyOf(mismatchedFields);
        }

        @Override
        public String toString() {
            return "CheckedRequest[id=" + id + ", status=" + status.code() + ", personal data redacted]";
        }
    }

    public Outcome check(TenantId tenant, HolderRef holder) {
        long started = System.nanoTime();
        access.requireInProgress(tenant, holder);
        // Read before anything else: a withdrawal deletes the session, and the trace still needs the journey.
        UUID journeyId = sessions.journeyOf(tenant, holder)
                .orElseThrow(() -> new ProblemException(ProblemCode.JOURNEY_CLOSED));
        HolderIdentity identity = identity(tenant, journeyId);
        PersonalIdentifier searched = toDomain(identity);
        AuthenticSourceConfigs.Current current = configs.current(tenant);
        Trace trace = new Trace(tenant, journeyId, current, searched.type(), started);
        if (current.hasSource() && !current.identifierTypes().contains(searched.type())) {
            // Not an error of the tenant: a login whose identifier the source cannot search by (design.md §3.2).
            log.info("Authentic source of tenant {} for {} does not accept {}: not asked", tenant,
                    current.attestationType(), searched.type().code());
            return notConfirmed(tenant, holder, trace, Optional.of(NotConfirmedReason.NOT_FOUND), List.of(),
                    List.of());
        }
        SourceRequests answer = ask(tenant, current, identity, searched, trace);
        // Only a list of requests has ids and requests under review to keep.
        Optional<EntitlementRecord> previous = answer instanceof SourceRequests.Requests
                ? previous(tenant, journeyId) : Optional.empty();
        // The trace says what the source answered before acting on it, so a failure afterwards keeps it.
        EntitlementDecision decision = EntitlementRules.decide(answer, underReview(previous));
        List<Entry> entries = entries(decision.requests(), previous, current, identity);
        return switch (decision) {
            case EntitlementDecision.Confirmed _ -> {
                trace.write(AuthenticSourceAuditTrail.Result.CONFIRMED, null, decision.requests());
                confirm(tenant, holder, journeyId, current.hasSource() ? searched.type() : null, identity, entries);
                yield new Outcome.Confirmed(checked(entries));
            }
            case EntitlementDecision.NotConfirmed(var reason, var requests) ->
                    notConfirmed(tenant, holder, trace, reason, requests, entries);
        };
    }

    private Outcome notConfirmed(TenantId tenant, HolderRef holder, Trace trace, Optional<NotConfirmedReason> reason,
                                 List<SourceRequest> requests, List<Entry> entries) {
        trace.write(AuthenticSourceAuditTrail.Result.NOT_CONFIRMED, reason.orElse(null), requests);
        withdrawal.withdraw(tenant, holder, JourneyWithdrawal.Cause.NOT_ENTITLED);
        return new Outcome.NotConfirmed(reason, checked(entries));
    }

    /**
     * The source's answer, or the holder's single request for an attestation type without a source (FR-06).
     * An unavailable source is traced here and thrown.
     */
    private SourceRequests ask(TenantId tenant, AuthenticSourceConfigs.Current current, HolderIdentity identity,
                               PersonalIdentifier searched, Trace trace) {
        if (!current.hasSource()) {
            // Allowed by FR-06, but it also happens when the source was left out by mistake: make it visible.
            log.warn("Entitlement of tenant {} confirmed for {} without an authentic source: identity only",
                    tenant, current.attestationType());
            return SourceRequests.of(new SourceRequest(NO_SOURCE_REF, RequestStatus.AVAILABLE, List.of(), false,
                    null, null, null, null, Map.of()));
        }
        try {
            SourceRequests answer = lookup(tenant, current,
                    new Holder(identity.givenName(), identity.familyName(), searched));
            requireDistinctReferences(tenant, current, answer);
            return answer;
        } catch (ProblemException unavailable) {
            trace.write(AuthenticSourceAuditTrail.Result.UNAVAILABLE, null, List.of());
            throw unavailable;
        }
    }

    /** The module's ids follow the source's references: two requests with the same one cannot be told apart. */
    private static void requireDistinctReferences(TenantId tenant, AuthenticSourceConfigs.Current current,
                                                  SourceRequests answer) {
        if (answer instanceof SourceRequests.Requests(var requests)
                && requests.stream().map(SourceRequest::sourceRef).distinct().count() != requests.size()) {
            log.warn("Authentic source {} of tenant {} gave two requests with the same reference",
                    current.adapter().type(), tenant);
            throw unavailable();
        }
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
        void write(AuthenticSourceAuditTrail.Result result, NotConfirmedReason reason, List<SourceRequest> requests) {
            try {
                trail.append(tenant, new AuthenticSourceAuditTrail.Event(UUID.randomUUID(), journeyId,
                        current.attestationType(),
                        current.hasSource() ? current.adapter().type() : AuthenticSourceAuditTrail.NO_ADAPTER,
                        identifierType, result, Optional.ofNullable(reason), countByStatus(requests),
                        Duration.ofNanos(System.nanoTime() - started), clock.instant()));
            } catch (RuntimeException e) {
                log.error("Could not record the entitlement check of journey {}", journeyId, e);
            }
        }
    }

    private static Map<RequestStatus, Integer> countByStatus(List<SourceRequest> requests) {
        Map<RequestStatus, Integer> counts = new EnumMap<>(RequestStatus.class);
        requests.forEach(request -> counts.merge(request.status(), 1, Integer::sum));
        return counts;
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

    /**
     * What an earlier check of this journey left, to keep each request's id and what the holder put under
     * review (design.md AD-6). A section that cannot be read is replaced by the new check.
     */
    private Optional<EntitlementRecord> previous(TenantId tenant, UUID journeyId) {
        return sessions.read(tenant, journeyId, Section.ENTITLEMENT).flatMap(section -> {
            try {
                return Optional.of(EntitlementRecord.fromJson(section));
            } catch (InvalidSectionException e) {
                log.warn("Earlier entitlement of the journey session is invalid at {}: replaced", e.field());
                return Optional.empty();
            }
        });
    }

    private static Set<String> underReview(Optional<EntitlementRecord> previous) {
        Set<String> references = new HashSet<>();
        previous.ifPresent(record -> record.requests().stream()
                .filter(request -> request.status() == EntitlementRecord.Status.UNDER_REVIEW)
                .forEach(request -> references.add(request.sourceRef())));
        return references;
    }

    /**
     * Asks the source on a virtual thread and waits at most the configured timeout. Any failure, including
     * an adapter that does not answer, is an unavailable source: never a confirmed one (FR-05).
     */
    private SourceRequests lookup(TenantId tenant, AuthenticSourceConfigs.Current current, Holder person) {
        Duration timeout = current.settings().timeout();
        // A slow source must not take every thread of the service, nor the slots of other tenants, with it.
        LookupSlots.Slot slot = lookupSlots.tryAcquire(tenant).orElseThrow(() -> {
            log.warn("Authentic source {} of tenant {} not asked: too many searches in flight",
                    current.adapter().type(), tenant);
            return unavailable();
        });
        Future<SourceRequests> answer;
        try {
            answer = lookups.submit(() -> {
                if (!slot.start()) {
                    // Cancelled before it started: whoever stopped waiting already gave the slot back.
                    return null;
                }
                try {
                    return current.adapter().lookup(tenant, current.attestationType(), person, current.settings(), timeout);
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

    /** A request of the check with what the journey keeps of it: its id, its data placed and its preview. */
    private record Entry(String id, SourceRequest request, Map<String, Object> claims, AttestationPreview preview) {
    }

    /**
     * The check's requests with their ids (design.md AD-6): a reference the journey already knew keeps its id,
     * a new one gets the next free number, never one an earlier request had. The data of a request that carries
     * it is placed as the tenant's mapping says, without changing its values.
     */
    private static List<Entry> entries(List<SourceRequest> requests, Optional<EntitlementRecord> previous,
                                       AuthenticSourceConfigs.Current current, HolderIdentity identity) {
        Map<String, String> known = new HashMap<>();
        int highest = 0;
        for (EntitlementRecord.Request earlier : previous.map(EntitlementRecord::requests).orElse(List.of())) {
            known.put(earlier.sourceRef(), earlier.id());
            highest = Math.max(highest, number(earlier.id()));
        }
        List<Entry> entries = new ArrayList<>();
        for (SourceRequest request : requests) {
            String id = known.get(request.sourceRef());
            if (id == null) {
                id = ID_PREFIX + ++highest;
            }
            entries.add(withData(id, request, current, identity));
        }
        return entries;
    }

    private static int number(String id) {
        try {
            return id.startsWith(ID_PREFIX) ? Integer.parseInt(id.substring(ID_PREFIX.length())) : 0;
        } catch (NumberFormatException _) {
            return 0;
        }
    }

    private static Entry withData(String id, SourceRequest request, AuthenticSourceConfigs.Current current,
                                  HolderIdentity identity) {
        boolean carriesData = request.status() == RequestStatus.AVAILABLE
                || request.status() == RequestStatus.UNDER_REVIEW;
        if (!carriesData) {
            return new Entry(id, request, Map.of(), null);
        }
        SourceSettings settings = current.settings();
        if (settings == null) {
            return new Entry(id, request, Map.of(), AttestationPreview.of(current.attestationType(),
                    identity.givenName(), identity.familyName(), List.of(), Map.of(), identity.email(), null));
        }
        Map<String, Object> claims = AttestationTranslation.translate(settings.mapping(), request.attributes());
        Object sourceEmail = settings.emailAttribute() == null ? null
                : request.attributes().get(settings.emailAttribute());
        return new Entry(id, request, claims, AttestationPreview.of(settings.typeName(), identity.givenName(),
                identity.familyName(), settings.mapping(), claims, identity.email(), sourceEmail));
    }

    private static List<CheckedRequest> checked(List<Entry> entries) {
        return entries.stream().map(entry -> new CheckedRequest(entry.id(), entry.request().status(),
                entry.request().mismatchedFields(), entry.request().renewal(), entry.request().requestedBy(),
                entry.request().requestedAt(), entry.request().displayIdentifier(), entry.request().organization(),
                Optional.ofNullable(entry.preview()))).toList();
    }

    /**
     * The entitlement and the step commit together: a journey never reaches the step without its data.
     *
     * @param searchedBy the identity's identifier type, {@code null} when the attestation type has no source
     */
    private void confirm(TenantId tenant, HolderRef holder, UUID journeyId, IdentifierType searchedBy,
                         HolderIdentity identity, List<Entry> entries) {
        // The email the Email step is prefilled with: the identity's, else the first one a source request gave.
        String email = entries.stream().map(Entry::preview).filter(Objects::nonNull)
                .map(AttestationPreview::email).filter(Objects::nonNull).findFirst().orElse(identity.email());
        EntitlementRecord entitlement = new EntitlementRecord(toSection(searchedBy), email,
                entries.stream().map(EntitlementService::toSection).toList(), clock.instant());
        transactions.executeWithoutResult(status -> {
            sessions.write(tenant, journeyId, Section.ENTITLEMENT, entitlement.toJson());
            progress.reach(tenant, holder, JourneyProgress.Step.ENTITLEMENT_CONFIRMED);
        });
    }

    private static es.in2.issuanceportal.session.api.IdentifierType toSection(IdentifierType type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case PERSONAL_ID -> es.in2.issuanceportal.session.api.IdentifierType.PERSONAL_ID;
            case COLEGIADO -> es.in2.issuanceportal.session.api.IdentifierType.COLEGIADO;
        };
    }

    private static EntitlementRecord.Request toSection(Entry entry) {
        SourceRequest request = entry.request();
        EntitlementRecord.Preview preview = entry.preview() == null ? null : new EntitlementRecord.Preview(
                entry.preview().typeName(), entry.preview().fields().stream()
                .map(field -> new EntitlementRecord.Field(field.label(), field.value())).toList());
        return new EntitlementRecord.Request(entry.id(), request.sourceRef(),
                EntitlementRecord.Status.fromCode(request.status().code()).orElseThrow(),
                request.mismatchedFields().stream().map(MismatchedField::code).toList(), request.renewal(),
                request.requestedBy(), request.requestedAt(), request.displayIdentifier(), request.organization(),
                entry.claims(), preview);
    }

    private static PersonalIdentifier toDomain(HolderIdentity identity) {
        IdentifierType type = switch (identity.personalIdentifier().type()) {
            case PERSONAL_ID -> IdentifierType.PERSONAL_ID;
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
