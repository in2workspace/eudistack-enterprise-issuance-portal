package es.in2.issuanceportal.session.infrastructure.persistence;

import es.in2.issuanceportal.session.api.HolderAlreadyHasJourneyException;
import es.in2.issuanceportal.session.api.HolderRef;
import es.in2.issuanceportal.session.api.JourneySessions;
import es.in2.issuanceportal.session.api.Section;
import es.in2.issuanceportal.session.application.port.PersonalDataCipher;
import es.in2.issuanceportal.shared.error.ProblemCode;
import es.in2.issuanceportal.shared.error.ProblemException;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code journey_session} in the tenant's schema. Every section is encrypted with the journey and the
 * section as context, so a value copied to another row or column cannot be decrypted.
 */
// "UPDATE " is written in each statement (write, clear): extracting the keyword would rewrite write and
// read, which carry the java:S2077 false-positive review, so the duplicate literal is accepted (as in
// JdbcJourneyRepository).
@SuppressWarnings("java:S1192")
@Component
class JdbcJourneySessions implements JourneySessions {

    private final JdbcClient jdbc;
    private final PersonalDataCipher cipher;

    JdbcJourneySessions(JdbcClient jdbc, PersonalDataCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @Override
    public void open(TenantId tenant, HolderRef holder, UUID journeyId) {
        try {
            jdbc.sql("INSERT INTO " + table(tenant) + " (journey_id, holder_ref) VALUES (:journey, :holder)")
                    .param("journey", journeyId)
                    .param("holder", holder.value())
                    .update();
        } catch (DuplicateKeyException e) {
            throw new HolderAlreadyHasJourneyException(e);
        }
    }

    @Override
    public Optional<UUID> journeyOf(TenantId tenant, HolderRef holder) {
        return jdbc.sql("SELECT journey_id FROM " + table(tenant) + " WHERE holder_ref = :holder")
                .param("holder", holder.value())
                .query(UUID.class)
                .optional();
    }

    @Override
    public Optional<String> read(TenantId tenant, UUID journeyId, Section section) {
        return jdbc.sql("SELECT " + column(section) + " FROM " + table(tenant) + " WHERE journey_id = :journey")
                .param("journey", journeyId)
                .query((rs, row) -> Optional.ofNullable(rs.getBytes(1)))
                .optional()
                .orElseThrow(() -> new ProblemException(ProblemCode.JOURNEY_CLOSED))
                .map(sealed -> new String(cipher.decrypt(sealed, context(journeyId, section)), StandardCharsets.UTF_8));
    }

    @Override
    public void write(TenantId tenant, UUID journeyId, Section section, String content) {
        byte[] sealed = cipher.encrypt(content.getBytes(StandardCharsets.UTF_8), context(journeyId, section));
        int updated = jdbc.sql("UPDATE " + table(tenant) + " SET " + column(section) + " = :value WHERE journey_id = :journey")
                .param("value", sealed)
                .param("journey", journeyId)
                .update();
        if (updated == 0) {
            throw new ProblemException(ProblemCode.JOURNEY_CLOSED);
        }
    }

    @Override
    // java:S2077 false positive, as in write and read: the table comes from the validated TenantId schema and the
    // column from the closed Section switch; the only value is bound.
    @SuppressWarnings("java:S2077")
    public void clear(TenantId tenant, UUID journeyId, Section section) {
        int updated = jdbc.sql("UPDATE " + table(tenant) + " SET " + column(section) + " = NULL WHERE journey_id = :journey")
                .param("journey", journeyId)
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
            case EMAIL_VERIFICATION -> "email_verification";
        };
    }

    private static byte[] context(UUID journeyId, Section section) {
        return (journeyId + "/" + section.name()).getBytes(StandardCharsets.UTF_8);
    }
}
