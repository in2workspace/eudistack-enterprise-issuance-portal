package es.in2.issuanceportal.journey.application;

import es.in2.issuanceportal.journey.api.SavedRequests;
import es.in2.issuanceportal.journey.application.port.JourneyIssuanceRepository;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** A request is saved when its process in {@code journey_issuance} has {@code saved_at}. */
@Service
class SavedRequestsService implements SavedRequests {

    private final JourneyIssuanceRepository issuances;

    SavedRequestsService(JourneyIssuanceRepository issuances) {
        this.issuances = issuances;
    }

    @Override
    public boolean isSaved(TenantId tenant, UUID journeyId, String requestId) {
        return issuances.findByJourney(tenant, journeyId).stream()
                .anyMatch(issuance -> issuance.requestId().equals(requestId) && issuance.isSaved());
    }
}
