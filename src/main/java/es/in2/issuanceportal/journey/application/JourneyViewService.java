package es.in2.issuanceportal.journey.application;

import es.in2.issuanceportal.journey.domain.EmailVerification;
import es.in2.issuanceportal.journey.domain.Journey;
import es.in2.issuanceportal.journey.domain.JourneyState;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * {@code GET /journey} (annex/openapi.yaml, {@code Journey}): the journey with what the interface shows of
 * it, the attestation preview the Authentic source module kept and the offer this module obtained
 * (EUD-277 ac.md decision 8). The journey is checked first, exactly as {@link JourneyService#current}
 * does, so a closed journey still answers {@code journey_closed}. Its sections are read afterwards, only
 * to show them: a section that cannot be read is left out with a warning, never a failed read of the
 * journey (EC-11).
 */
@Slf4j
@Service
public class JourneyViewService {

    private final JourneyService journeys;
    private final JourneySessions sessions;
    private final PortalConfigService configs;

    public JourneyViewService(JourneyService journeys, JourneySessions sessions, PortalConfigService configs) {
        this.journeys = journeys;
        this.sessions = sessions;
        this.configs = configs;
    }

    /**
     * The journey and, when the session still has them, its preview (AC-16), its offer (AC-17) and its email
     * verification, from the first code sent until the journey obtains its offer or ends (EUD-358).
     */
    public record View(Journey journey, Optional<Attestation> attestation,
                       Optional<IssuanceService.Offer> offer, Optional<EmailVerification> emailVerification) {
    }

    /**
     * What the Data step shows: the preview of one request and the email the holder is known by. Until
     * EUD-359 task 3 it is the preview of the first available request.
     */
    public record Attestation(String typeName, List<EntitlementRecord.Field> fields, String email,
                              boolean emailRequired) {

        @Override
        public String toString() {
            return "Attestation[typeName=" + typeName + ", fields=" + fields.size() + ", emailRequired="
                    + emailRequired + ", personal data redacted]";
        }
    }

    public View current(TenantId tenant, HolderRef holder) {
        Journey journey = journeys.current(tenant, holder);
        // EUD-359 task 3 replaces this with per-request confirmation
        Optional<Attestation> attestation = read(tenant, journey.id(), Section.ENTITLEMENT,
                EntitlementRecord::fromJson).flatMap(JourneyViewService::attestation);
        Optional<IssuanceService.Offer> offer = read(tenant, journey.id(), Section.ISSUANCE,
                IssuanceRecord::fromJson).flatMap(issuance -> offer(tenant, journey.id(), issuance));
        Optional<EmailVerification> emailVerification = journey.state() == JourneyState.IN_PROGRESS
                ? read(tenant, journey.id(), Section.EMAIL_VERIFICATION, JourneyViewService::verification)
                : Optional.empty();
        return new View(journey, attestation, offer, emailVerification);
    }

    private static Optional<Attestation> attestation(EntitlementRecord entitlement) {
        return entitlement.firstAvailable().map(EntitlementRecord.Request::preview)
                .map(preview -> new Attestation(preview.typeName(), preview.fields(), entitlement.email(),
                        entitlement.email() == null));
    }

    private static EmailVerification verification(String section) {
        return EmailVerificationService.fromRecord(EmailVerificationRecord.fromJson(section));
    }

    /**
     * The stored offer, with its onboarding address when it has one. In a tenant that uses onboarding
     * tickets an offer without its address is never shown: the interface would build the wallet address
     * with it (EC-10, EUD-286 AC-09). A configuration that cannot be read counts as one with tickets.
     */
    private Optional<IssuanceService.Offer> offer(TenantId tenant, UUID journeyId, IssuanceRecord issuance) {
        if (issuance.offerUri().isEmpty()) {
            return Optional.empty();
        }
        if (issuance.onboarding().isEmpty() && usesOnboardingTickets(tenant)) {
            log.warn("Journey {} has an offer without its onboarding address in a tenant with tickets: not shown",
                    journeyId);
            return Optional.empty();
        }
        return Optional.of(new IssuanceService.Offer(issuance.offerUri().get(), issuance.offerExpiresAt(),
                issuance.onboarding().orElse(null), issuance.onboardingExpiresAt()));
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
