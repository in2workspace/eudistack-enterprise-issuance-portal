package es.in2.issuanceportal.session.api;

import es.in2.issuanceportal.shared.tenant.TenantId;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The journey session shared by the three modules (design.md AD-3). Section contents are opaque JSON:
 * each module defines and serialises its own, so the session knows none of them. Reading or writing a
 * session that does not exist answers {@code journey_closed}.
 */
public interface JourneySessions {

    void open(TenantId tenant, SessionId session, UUID journeyId);

    /** The journey the session belongs to, if the session still exists. */
    Optional<UUID> journeyOf(TenantId tenant, SessionId session);

    Optional<String> read(TenantId tenant, SessionId session, Section section);

    void write(TenantId tenant, SessionId session, Section section, String content);

    /** Physically deletes the session of the journey, with all its personal data. Idempotent. */
    void close(TenantId tenant, UUID journeyId);

    /** Replaces the session identifier, keeping the content. The old identifier stops working. */
    SessionId rotate(TenantId tenant, SessionId session);

    /** The journeys that still have a session, that is, still hold personal data. */
    List<UUID> openJourneys(TenantId tenant);
}
