package es.in2.issuanceportal.journey.application;

import es.in2.issuanceportal.journey.domain.Journey;
import es.in2.issuanceportal.session.api.EntitlementRecord;
import es.in2.issuanceportal.session.api.InvalidSectionException;
import es.in2.issuanceportal.session.api.IssuanceRecord;
import es.in2.issuanceportal.session.api.JourneySessions;
import es.in2.issuanceportal.session.api.Section;
import es.in2.issuanceportal.session.api.SessionId;
import es.in2.issuanceportal.shared.error.ProblemException;
import es.in2.issuanceportal.shared.tenant.TenantId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

    /** The journey and, when the session still has them, its preview (AC-16) and its offer (AC-17). */
    public record View(Journey journey, Optional<EntitlementRecord.Preview> attestation,
                       Optional<IssuanceService.Offer> offer) {
    }

    public View current(TenantId tenant, SessionId session) {
        Journey journey = journeys.current(tenant, session);
        Optional<EntitlementRecord.Preview> attestation = read(tenant, session, journey.id(), Section.ENTITLEMENT,
                EntitlementRecord::fromJson).map(EntitlementRecord::preview);
        Optional<IssuanceService.Offer> offer = read(tenant, session, journey.id(), Section.ISSUANCE,
                IssuanceRecord::fromJson).flatMap(issuance -> offer(tenant, journey.id(), issuance));
        return new View(journey, attestation, offer);
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
    private <T> Optional<T> read(TenantId tenant, SessionId session, UUID journeyId, Section section,
                                 Function<String, T> parse) {
        try {
            return sessions.read(tenant, session, section).map(parse);
        } catch (InvalidSectionException e) {
            log.warn("Section {} of journey {} is invalid at {}: not shown", section, journeyId, e.field());
        } catch (IllegalArgumentException _) {
            log.warn("Section {} of journey {} cannot be read: not shown", section, journeyId);
        }
        return Optional.empty();
    }
}
