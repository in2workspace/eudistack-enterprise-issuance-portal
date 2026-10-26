package es.in2.issuanceportal.journey;

import es.in2.issuanceportal.journey.application.port.JourneyRepository;
import es.in2.issuanceportal.journey.domain.Journey;
import es.in2.issuanceportal.journey.domain.JourneyState;
import es.in2.issuanceportal.session.InMemoryJourneySessions;
import es.in2.issuanceportal.shared.tenant.TenantId;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Journeys in memory, storing copies so tests see only what was saved, like the database. The expiry
 * queries also look at the sessions, as the JDBC adapter does with a join.
 */
public class InMemoryJourneyRepository implements JourneyRepository {

    private final Map<String, Journey> journeys = new ConcurrentHashMap<>();
    private final InMemoryJourneySessions sessions;

    public InMemoryJourneyRepository(InMemoryJourneySessions sessions) {
        this.sessions = sessions;
    }

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
    public List<UUID> findExpiryCandidates(TenantId tenant, Instant cutoff, UUID afterId, int limit) {
        // Text order is PostgreSQL's uuid order; UUID.compareTo is signed and would differ.
        return sessions.openJourneys(tenant).stream()
                .filter(id -> id.toString().compareTo(afterId.toString()) > 0)
                .filter(id -> isClosedOrInactive(journeys.get(key(tenant, id)), cutoff))
                .sorted(java.util.Comparator.comparing(UUID::toString))
                .limit(limit)
                .toList();
    }

    @Override
    public boolean updateIfInactive(TenantId tenant, Journey journey, Instant cutoff) {
        Journey stored = journeys.get(key(tenant, journey.id()));
        if (stored == null || !stored.isInProgress() || stored.lastActivity().isAfter(cutoff)) {
            return false;
        }
        journeys.put(key(tenant, journey.id()), copy(journey));
        return true;
    }

    @Override
    public boolean deleteSessionIfClosedOrInactive(TenantId tenant, UUID journeyId, Instant cutoff) {
        if (!sessions.openJourneys(tenant).contains(journeyId)
                || !isClosedOrInactive(journeys.get(key(tenant, journeyId)), cutoff)) {
            return false;
        }
        sessions.close(tenant, journeyId);
        return true;
    }

    private static boolean isClosedOrInactive(Journey journey, Instant cutoff) {
        return journey != null
                && (journey.state() == JourneyState.WITHDRAWN || !journey.lastActivity().isAfter(cutoff));
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
