package es.in2.issuanceportal.session.infrastructure.persistence;

import es.in2.issuanceportal.session.api.JourneySessions;
import es.in2.issuanceportal.session.api.Section;
import es.in2.issuanceportal.session.api.SessionId;
import es.in2.issuanceportal.session.application.port.PersonalDataCipher;
import es.in2.issuanceportal.shared.error.ProblemCode;
import es.in2.issuanceportal.shared.error.ProblemException;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code journey_session} in the tenant's schema. Every section is encrypted with the journey and the
 * section as context, so a value copied to another row or column cannot be decrypted.
 */
@Component
class JdbcJourneySessions implements JourneySessions {

    private final JdbcClient jdbc;
    private final PersonalDataCipher cipher;

    JdbcJourneySessions(JdbcClient jdbc, PersonalDataCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @Override
    public void open(TenantId tenant, SessionId session, UUID journeyId) {
        jdbc.sql("INSERT INTO " + table(tenant) + " (session_id_hash, journey_id) VALUES (:hash, :journey)")
                .param("hash", session.hash())
                .param("journey", journeyId)
                .update();
    }

    @Override
    public Optional<UUID> journeyOf(TenantId tenant, SessionId session) {
        return jdbc.sql("SELECT journey_id FROM " + table(tenant) + " WHERE session_id_hash = :hash")
                .param("hash", session.hash())
                .query(UUID.class)
                .optional();
    }

    @Override
    public Optional<String> read(TenantId tenant, SessionId session, Section section) {
        UUID journeyId = existingJourney(tenant, session);
        return jdbc.sql("SELECT " + column(section) + " FROM " + table(tenant) + " WHERE session_id_hash = :hash")
                .param("hash", session.hash())
                .query((rs, row) -> Optional.ofNullable(rs.getBytes(1)))
                .optional()
                .flatMap(value -> value)
                .map(sealed -> new String(cipher.decrypt(sealed, context(journeyId, section)), StandardCharsets.UTF_8));
    }

    @Override
    public void write(TenantId tenant, SessionId session, Section section, String content) {
        UUID journeyId = existingJourney(tenant, session);
        byte[] sealed = cipher.encrypt(content.getBytes(StandardCharsets.UTF_8), context(journeyId, section));
        int updated = jdbc.sql("UPDATE " + table(tenant) + " SET " + column(section) + " = :value WHERE session_id_hash = :hash")
                .param("value", sealed)
                .param("hash", session.hash())
                .update();
        if (updated == 0) {
            throw new ProblemException(ProblemCode.JOURNEY_CLOSED);
        }
    }

    @Override
    public void close(TenantId tenant, UUID journeyId) {
        jdbc.sql("DELETE FROM " + table(tenant) + " WHERE journey_id = :journey")
                .param("journey", journeyId)
                .update();
    }

    @Override
    public SessionId rotate(TenantId tenant, SessionId session) {
        SessionId rotated = SessionId.generate();
        int updated = jdbc.sql("UPDATE " + table(tenant) + " SET session_id_hash = :rotated WHERE session_id_hash = :hash")
                .param("rotated", rotated.hash())
                .param("hash", session.hash())
                .update();
        if (updated == 0) {
            throw new ProblemException(ProblemCode.JOURNEY_CLOSED);
        }
        return rotated;
    }

    private UUID existingJourney(TenantId tenant, SessionId session) {
        return journeyOf(tenant, session).orElseThrow(() -> new ProblemException(ProblemCode.JOURNEY_CLOSED));
    }

    private static String table(TenantId tenant) {
        return tenant.schema() + ".journey_session";
    }

    /** Column names come from this switch only, never from input. */
    private static String column(Section section) {
        return switch (section) {
            case IDENTITY -> "identity";
            case ENTITLEMENT -> "entitlement";
            case ISSUANCE -> "issuance";
            case EMAIL -> "email";
        };
    }

    private static byte[] context(UUID journeyId, Section section) {
        return (journeyId + "/" + section.name()).getBytes(StandardCharsets.UTF_8);
    }
}
