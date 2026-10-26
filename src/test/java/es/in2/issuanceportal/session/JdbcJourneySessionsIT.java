package es.in2.issuanceportal.session;

import es.in2.issuanceportal.TestcontainersConfiguration;
import es.in2.issuanceportal.session.api.JourneySessions;
import es.in2.issuanceportal.session.api.Section;
import es.in2.issuanceportal.session.api.SessionId;
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
    void write_personalData_storesNeitherIdentifierNorDataInClear(Section section) {
        SessionId session = SessionId.generate();
        UUID journey = newJourney(SANDBOX);
        sessions.open(SANDBOX, session, journey);
        sessions.write(SANDBOX, session, section, "{\"givenName\":\"Ana\",\"dni\":\"12345678Z\"}");

        String hash = jdbc.sql("SELECT session_id_hash FROM \"sandbox_issuance_portal\".journey_session WHERE journey_id = :j")
                .param("j", journey).query(String.class).single();
        // Column names come from a fixed switch in the adapter, mirrored here.
        String column = section.name().toLowerCase(java.util.Locale.ROOT);
        byte[] stored = jdbc.sql("SELECT " + column + " FROM \"sandbox_issuance_portal\".journey_session WHERE journey_id = :j")
                .param("j", journey).query(byte[].class).single();

        assertThat(hash).isEqualTo(session.hash()).isNotEqualTo(session.value());
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("12345678Z").doesNotContain("Ana");
    }

    @Test
    void read_valueCopiedFromAnotherJourney_throws() {
        SessionId victim = SessionId.generate();
        UUID victimJourney = newJourney(SANDBOX);
        sessions.open(SANDBOX, victim, victimJourney);
        sessions.write(SANDBOX, victim, Section.IDENTITY, "{\"dni\":\"12345678Z\"}");
        SessionId attacker = SessionId.generate();
        UUID attackerJourney = newJourney(SANDBOX);
        sessions.open(SANDBOX, attacker, attackerJourney);

        jdbc.sql("UPDATE \"sandbox_issuance_portal\".journey_session SET identity = "
                        + "(SELECT identity FROM \"sandbox_issuance_portal\".journey_session WHERE journey_id = :victim) "
                        + "WHERE journey_id = :attacker")
                .param("victim", victimJourney).param("attacker", attackerJourney).update();

        assertThatIllegalArgumentException().isThrownBy(() -> sessions.read(SANDBOX, attacker, Section.IDENTITY));
    }
}
