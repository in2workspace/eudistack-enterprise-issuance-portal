package es.in2.issuanceportal.session;

import es.in2.issuanceportal.session.api.HolderAlreadyHasJourneyException;
import es.in2.issuanceportal.session.api.HolderRef;
import es.in2.issuanceportal.session.api.JourneySessions;
import es.in2.issuanceportal.session.api.Section;
import es.in2.issuanceportal.shared.error.ProblemCode;
import es.in2.issuanceportal.shared.error.ProblemException;
import es.in2.issuanceportal.shared.tenant.TenantId;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The session port in memory, so each module is tested without the database or the other modules
 * (design.md AD-3). Behaves like the JDBC adapter: {@code JourneySessionsContract} runs against both.
 */
public class InMemoryJourneySessions implements JourneySessions {

    private final Map<String, Entry> sessions = new ConcurrentHashMap<>();

    @Override
    public synchronized void open(TenantId tenant, HolderRef holder, UUID journeyId) {
        if (journeyOf(tenant, holder).isPresent()) {
            throw new HolderAlreadyHasJourneyException(null);
        }
        sessions.put(key(tenant, journeyId), new Entry(holder, journeyId, new EnumMap<>(Section.class)));
    }

    @Override
    public Optional<UUID> journeyOf(TenantId tenant, HolderRef holder) {
        return sessions.entrySet().stream()
                .filter(e -> e.getKey().startsWith(tenant.value() + "/") && e.getValue().holder().equals(holder))
                .map(e -> e.getValue().journeyId())
                .findFirst();
    }

    @Override
    public Optional<String> read(TenantId tenant, UUID journeyId, Section section) {
        return Optional.ofNullable(existing(tenant, journeyId).sections().get(section));
    }

    @Override
    public void write(TenantId tenant, UUID journeyId, Section section, String content) {
        existing(tenant, journeyId).sections().put(section, content);
    }

    @Override
    public void clear(TenantId tenant, UUID journeyId, Section section) {
        existing(tenant, journeyId).sections().remove(section);
    }

    @Override
    public void close(TenantId tenant, UUID journeyId) {
        sessions.remove(key(tenant, journeyId));
    }

    /** For the in-memory journey repository: the journeys that still have a session. */
    public List<UUID> openJourneys(TenantId tenant) {
        return sessions.entrySet().stream()
                .filter(e -> e.getKey().startsWith(tenant.value() + "/"))
                .map(e -> e.getValue().journeyId())
                .toList();
    }

    private Entry existing(TenantId tenant, UUID journeyId) {
        Entry entry = sessions.get(key(tenant, journeyId));
        if (entry == null) {
            throw new ProblemException(ProblemCode.JOURNEY_CLOSED);
        }
        return entry;
    }

    private static String key(TenantId tenant, UUID journeyId) {
        return tenant.value() + "/" + journeyId;
    }

    private record Entry(HolderRef holder, UUID journeyId, Map<Section, String> sections) {
    }
}
