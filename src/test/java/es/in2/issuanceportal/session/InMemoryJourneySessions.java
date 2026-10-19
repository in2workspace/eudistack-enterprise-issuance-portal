package es.in2.issuanceportal.session;

import es.in2.issuanceportal.session.api.JourneySessions;
import es.in2.issuanceportal.session.api.Section;
import es.in2.issuanceportal.session.api.SessionId;
import es.in2.issuanceportal.shared.error.ProblemCode;
import es.in2.issuanceportal.shared.error.ProblemException;
import es.in2.issuanceportal.shared.tenant.TenantId;

import java.util.EnumMap;
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
    public void open(TenantId tenant, SessionId session, UUID journeyId) {
        sessions.put(key(tenant, session.hash()), new Entry(journeyId, new EnumMap<>(Section.class)));
    }

    @Override
    public Optional<UUID> journeyOf(TenantId tenant, SessionId session) {
        return Optional.ofNullable(sessions.get(key(tenant, session.hash()))).map(Entry::journeyId);
    }

    @Override
    public Optional<String> read(TenantId tenant, SessionId session, Section section) {
        return Optional.ofNullable(existing(tenant, session).sections().get(section));
    }

    @Override
    public void write(TenantId tenant, SessionId session, Section section, String content) {
        existing(tenant, session).sections().put(section, content);
    }

    @Override
    public void close(TenantId tenant, UUID journeyId) {
        sessions.entrySet().removeIf(e -> e.getKey().startsWith(tenant.value() + "/")
                && e.getValue().journeyId().equals(journeyId));
    }

    @Override
    public SessionId rotate(TenantId tenant, SessionId session) {
        Entry entry = sessions.remove(key(tenant, session.hash()));
        if (entry == null) {
            throw new ProblemException(ProblemCode.JOURNEY_CLOSED);
        }
        SessionId rotated = SessionId.generate();
        sessions.put(key(tenant, rotated.hash()), entry);
        return rotated;
    }

    private Entry existing(TenantId tenant, SessionId session) {
        Entry entry = sessions.get(key(tenant, session.hash()));
        if (entry == null) {
            throw new ProblemException(ProblemCode.JOURNEY_CLOSED);
        }
        return entry;
    }

    private static String key(TenantId tenant, String hash) {
        return tenant.value() + "/" + hash;
    }

    private record Entry(UUID journeyId, Map<Section, String> sections) {
    }
}
