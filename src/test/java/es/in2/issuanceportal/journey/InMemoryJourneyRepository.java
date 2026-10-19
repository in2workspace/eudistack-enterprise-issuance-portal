package es.in2.issuanceportal.journey;

import es.in2.issuanceportal.journey.application.port.JourneyRepository;
import es.in2.issuanceportal.journey.domain.Journey;
import es.in2.issuanceportal.journey.domain.JourneyState;
import es.in2.issuanceportal.shared.tenant.TenantId;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Journeys in memory, storing copies so tests see only what was saved, like the database. */
public class InMemoryJourneyRepository implements JourneyRepository {

    private final Map<String, Journey> journeys = new ConcurrentHashMap<>();

    @Override
    public void insert(TenantId tenant, Journey journey) {
        journeys.put(key(tenant, journey.id()), copy(journey));
    }

    @Override
    public boolean update(TenantId tenant, Journey journey, JourneyState readInState) {
        Journey stored = journeys.get(key(tenant, journey.id()));
        if (stored == null || stored.state() != readInState) {
            return false;
        }
        journeys.put(key(tenant, journey.id()), copy(journey));
        return true;
    }

    @Override
    public Optional<Journey> findById(TenantId tenant, UUID id) {
        return Optional.ofNullable(journeys.get(key(tenant, id))).map(InMemoryJourneyRepository::copy);
    }

    @Override
    public List<Journey> findInProgressInactiveSince(TenantId tenant, Instant cutoff, int limit) {
        return journeys.entrySet().stream()
                .filter(e -> e.getKey().startsWith(tenant.value() + "/"))
                .map(Map.Entry::getValue)
                .filter(j -> j.isInProgress() && !j.lastActivity().isAfter(cutoff))
                .sorted(Comparator.comparing(Journey::lastActivity))
                .limit(limit)
                .map(InMemoryJourneyRepository::copy)
                .toList();
    }

    private static String key(TenantId tenant, UUID id) {
        return tenant.value() + "/" + id;
    }

    private static Journey copy(Journey j) {
        return Journey.restore(j.id(), j.state(), j.step(), j.withdrawalReason().orElse(null),
                j.identificationMethod().orElse(null), j.issuanceProcessIds(),
                j.createdAt(), j.lastActivity(), j.endedAt().orElse(null));
    }
}
