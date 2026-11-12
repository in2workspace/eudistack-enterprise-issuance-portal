package es.in2.issuanceportal.session.api;

import es.in2.issuanceportal.shared.tenant.TenantId;

import java.util.Optional;
import java.util.UUID;

/**
 * The journey session shared by the three modules (design.md AD-3). Section contents are opaque JSON:
 * each module defines and serialises its own, so the session knows none of them. A session is reached
 * through its holder and then addressed by its journey. Reading or writing a session that does not exist
 * answers {@code journey_closed}.
 */
public interface JourneySessions {

    /**
     * Opens the session of a journey for its holder.
     *
     * @throws HolderAlreadyHasJourneyException when the holder has another session open
     */
    void open(TenantId tenant, HolderRef holder, UUID journeyId);

    /** The journey the holder can reach, if its session exists. */
    Optional<UUID> journeyOf(TenantId tenant, HolderRef holder);

    Optional<String> read(TenantId tenant, UUID journeyId, Section section);

    void write(TenantId tenant, UUID journeyId, Section section, String content);

    /** Empties one section of the session. Idempotent: an empty section stays empty. */
    void clear(TenantId tenant, UUID journeyId, Section section);

    /** Physically deletes the session of the journey, with all its personal data. Idempotent. */
    void close(TenantId tenant, UUID journeyId);
}
