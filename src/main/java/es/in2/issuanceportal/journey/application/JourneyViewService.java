package es.in2.issuanceportal.journey.application;

import es.in2.issuanceportal.journey.application.port.JourneyIssuanceRepository;
import es.in2.issuanceportal.journey.domain.EmailVerification;
import es.in2.issuanceportal.journey.domain.Journey;
import es.in2.issuanceportal.journey.domain.JourneyIssuance;
import es.in2.issuanceportal.session.api.EmailVerificationRecord;
import es.in2.issuanceportal.session.api.EntitlementRecord;
import es.in2.issuanceportal.session.api.HolderRef;
import es.in2.issuanceportal.session.api.InvalidSectionException;
import es.in2.issuanceportal.session.api.IssuanceRecord;
import es.in2.issuanceportal.session.api.JourneySessions;
import es.in2.issuanceportal.session.api.Section;
import es.in2.issuanceportal.shared.error.ProblemException;
import es.in2.issuanceportal.shared.tenant.TenantId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * {@code GET /journey} (annex/openapi.yaml, {@code Journey}): the journey with what the interface shows of
 * it: the email it can prefill, the email verification and each request with its status, what the Data step shows
 * and, once delivered, its current offer (design.md §3.7). The journey is checked first, exactly as
 * {@link JourneyService#current} does, so a closed journey still answers {@code journey_closed}. Its sections are read
 * afterwards, only to show them: a section that cannot be read is left out with a warning, never a failed read of
 * the journey (EC-11). The source's reference of a request, the identity and the data that is not the preview never
 * reach the view.
 */
@Slf4j
@Service
public class JourneyViewService {

    private final JourneyService journeys;
    private final JourneyIssuanceRepository issuances;
    private final JourneySessions sessions;
    private final PortalConfigService configs;

    public JourneyViewService(JourneyService journeys, JourneyIssuanceRepository issuances, JourneySessions sessions,
                              PortalConfigService configs) {
        this.journeys = journeys;
        this.issuances = issuances;
        this.sessions = sessions;
        this.configs = configs;
    }

    /** The journey and what its session still has to show of it. */
    public record View(Journey journey, Optional<String> knownEmail, List<RequestView> requests,
                       Optional<EmailVerification> emailVerification) {
    }

    /** What the Data step shows of a request. */
    public record Attestation(String typeName, List<EntitlementRecord.Field> fields) {

        @Override
        public String toString() {
            return "Attestation[typeName=" + typeName + ", fields=" + fields.size() + ", personal data redacted]";
        }
    }

    /** The status of a request in the journey, deduced as design.md §3.7 says. */
    public enum RequestStatus {

        AVAILABLE("available"),
        IN_OFFER("in_offer"),
        SAVED("saved"),
        NOT_AVAILABLE("not_available");

        private final String code;

        RequestStatus(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /**
     * One request of the journey. {@code notAvailableReason} is the status the Authentic source module gave it, only
     * when it is not available; {@code attestation} is there for an available or an in-offer request; {@code offer}
     * only for an in-offer one whose offer was delivered.
     */
    public record RequestView(String id, RequestStatus status, Optional<String> notAvailableReason,
                              Optional<String> requestedBy, Optional<Instant> requestedAt, boolean renewal,
                              Optional<String> displayIdentifier, Optional<String> organization,
                              Optional<Attestation> attestation, Optional<IssuanceService.Offer> offer) {

        @Override
        public String toString() {
            return "RequestView[id=" + id + ", status=" + status.code() + ", personal data and offer redacted]";
        }
    }

    public View current(TenantId tenant, HolderRef holder) {
        Journey journey = journeys.current(tenant, holder);
        Optional<EntitlementRecord> entitlement = read(tenant, journey.id(), Section.ENTITLEMENT,
                EntitlementRecord::fromJson);
        IssuanceRecord issuance = read(tenant, journey.id(), Section.ISSUANCE, IssuanceRecord::fromJson)
                .orElseGet(IssuanceRecord::empty);
        List<JourneyIssuance> processes = issuances.findByJourney(tenant, journey.id());
        List<RequestView> requests = entitlement.map(record -> record.requests().stream()
                .map(request -> view(tenant, journey.id(), request, processes, issuance)).toList())
                .orElse(List.of());
        Optional<EmailVerification> emailVerification = read(tenant, journey.id(), Section.EMAIL_VERIFICATION,
                JourneyViewService::verification);
        return new View(journey, entitlement.flatMap(EntitlementRecord::emailIfKnown), requests, emailVerification);
    }

    private RequestView view(TenantId tenant, UUID journeyId, EntitlementRecord.Request request,
                             List<JourneyIssuance> processes, IssuanceRecord issuance) {
        Optional<JourneyIssuance> process = processes.stream()
                .filter(candidate -> candidate.requestId().equals(request.id())).findFirst();
        RequestStatus status;
        if (process.isPresent()) {
            status = process.get().isSaved() ? RequestStatus.SAVED : RequestStatus.IN_OFFER;
        } else {
            status = request.status() == EntitlementRecord.Status.AVAILABLE
                    ? RequestStatus.AVAILABLE : RequestStatus.NOT_AVAILABLE;
        }
        Optional<String> reason = status == RequestStatus.NOT_AVAILABLE
                ? Optional.of(request.status().code()) : Optional.empty();
        boolean showsData = status == RequestStatus.AVAILABLE || status == RequestStatus.IN_OFFER;
        Optional<Attestation> attestation = showsData && request.preview() != null
                ? Optional.of(new Attestation(request.preview().typeName(), request.preview().fields()))
                : Optional.empty();
        Optional<IssuanceService.Offer> offer = status == RequestStatus.IN_OFFER
                ? issuance.entry(request.id()).flatMap(entry -> offer(tenant, journeyId, entry))
                : Optional.empty();
        return new RequestView(request.id(), status, reason, Optional.ofNullable(request.requestedBy()),
                Optional.ofNullable(request.requestedAt()), request.renewal(),
                Optional.ofNullable(request.displayIdentifier()), Optional.ofNullable(request.organization()),
                attestation, offer);
    }

    private static EmailVerification verification(String section) {
        return EmailVerificationService.fromRecord(EmailVerificationRecord.fromJson(section));
    }

    /**
     * The stored offer of a request, with its onboarding address when it has one. In a tenant that uses onboarding
     * tickets an offer without its address is never shown: the interface would build the wallet address
     * with it (EC-10, EUD-286 AC-09). A configuration that cannot be read counts as one with tickets.
     */
    private Optional<IssuanceService.Offer> offer(TenantId tenant, UUID journeyId, IssuanceRecord.Entry entry) {
        if (entry.offerUri().isEmpty()) {
            return Optional.empty();
        }
        if (entry.onboarding().isEmpty() && usesOnboardingTickets(tenant)) {
            log.warn("Journey {} has an offer without its onboarding address in a tenant with tickets: not shown",
                    journeyId);
            return Optional.empty();
        }
        return Optional.of(new IssuanceService.Offer(entry.offerUri().get(), entry.offerExpiresAt(),
                entry.onboarding().orElse(null), entry.onboardingExpiresAt()));
    }

    private boolean usesOnboardingTickets(TenantId tenant) {
        try {
            return configs.current(tenant).settings().onboarding().enabled();
        } catch (ProblemException _) {
            return true;
        }
    }

    /** A section that is not there is simply absent; one that cannot be decrypted or parsed is left out (EC-11). */
    private <T> Optional<T> read(TenantId tenant, UUID journeyId, Section section,
                                 Function<String, T> parse) {
        try {
            return sessions.read(tenant, journeyId, section).map(parse);
        } catch (InvalidSectionException e) {
            log.warn("Section {} of journey {} is invalid at {}: not shown", section, journeyId, e.field());
        } catch (IllegalArgumentException _) {
            log.warn("Section {} of journey {} cannot be read: not shown", section, journeyId);
        }
        return Optional.empty();
    }
}
