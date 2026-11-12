package es.in2.issuanceportal.session;

import es.in2.issuanceportal.TestcontainersConfiguration;
import es.in2.issuanceportal.session.api.HolderRef;
import es.in2.issuanceportal.session.api.JourneySessions;
import es.in2.issuanceportal.session.api.Section;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class JdbcJourneySessionsIT extends JourneySessionsContract {

    @Autowired
    private JourneySessions sessions;
    @Autowired
    private JdbcClient jdbc;

    @Override
    JourneySessions sessions() {
        return sessions;
    }

    @Override
    UUID newJourney(TenantId tenant) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO " + tenant.schema() + ".journey (id, state, step, created_at, last_activity) "
                        + "VALUES (:id, 'in_progress', 'started', now(), now())")
                .param("id", id)
                .update();
        return id;
    }

    @ParameterizedTest
    @EnumSource(Section.class)
    void write_personalData_storesNoDataInClear(Section section) {
        UUID journey = newJourney(SANDBOX);
        sessions.open(SANDBOX, Holders.next(), journey);
        sessions.write(SANDBOX, journey, section, "{\"givenName\":\"Ana\",\"dni\":\"12345678Z\"}");

        // Column names come from a fixed switch in the adapter, mirrored here.
        String column = section.name().toLowerCase(java.util.Locale.ROOT);
        byte[] stored = jdbc.sql("SELECT " + column + " FROM \"sandbox_issuance_portal\".journey_session WHERE journey_id = :j")
                .param("j", journey).query(byte[].class).single();

        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("12345678Z").doesNotContain("Ana");
    }

    @Test
    void open_holder_storesTheReferenceAndNothingElseOfTheHolder() {
        HolderRef holder = Holders.next();
        UUID journey = newJourney(SANDBOX);

        sessions.open(SANDBOX, holder, journey);

        String stored = jdbc.sql("SELECT holder_ref FROM \"sandbox_issuance_portal\".journey_session WHERE journey_id = :j")
                .param("j", journey).query(String.class).single();
        assertThat(stored).isEqualTo(holder.value());
    }

    @Test
    void read_valueCopiedFromAnotherJourney_throws() {
        UUID victimJourney = newJourney(SANDBOX);
        sessions.open(SANDBOX, Holders.next(), victimJourney);
        sessions.write(SANDBOX, victimJourney, Section.IDENTITY, "{\"dni\":\"12345678Z\"}");
        UUID attackerJourney = newJourney(SANDBOX);
        sessions.open(SANDBOX, Holders.next(), attackerJourney);

        jdbc.sql("UPDATE \"sandbox_issuance_portal\".journey_session SET identity = "
                        + "(SELECT identity FROM \"sandbox_issuance_portal\".journey_session WHERE journey_id = :victim) "
                        + "WHERE journey_id = :attacker")
                .param("victim", victimJourney).param("attacker", attackerJourney).update();

        assertThatIllegalArgumentException().isThrownBy(() -> sessions.read(SANDBOX, attackerJourney, Section.IDENTITY));
    }

    @Test
    void journeyOf_legacySessionsWithoutHolder_areNotReachedByAnyHolder() {
        UUID legacyA = newJourney(SANDBOX);
        UUID legacyB = newJourney(SANDBOX);

        // Opened with the cookie, before the migration: two rows without holder do not collide on the unique index.
        jdbc.sql("INSERT INTO \"sandbox_issuance_portal\".journey_session (journey_id) VALUES (:a), (:b)")
                .param("a", legacyA).param("b", legacyB).update();

        assertThat(sessions.journeyOf(SANDBOX, Holders.next())).isEmpty();
    }
}
