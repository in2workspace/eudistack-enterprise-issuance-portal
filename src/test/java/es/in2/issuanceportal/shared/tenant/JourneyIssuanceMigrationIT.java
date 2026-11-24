package es.in2.issuanceportal.shared.tenant;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V6 of the tenant schema against PostgreSQL, applied over journeys written by the single-process code before it. */
class JourneyIssuanceMigrationIT {

    private static final String SCHEMA = "legacy_issuance_portal";
    private static final Timestamp CREATED = Timestamp.from(Instant.parse("2026-11-01T10:00:00Z"));
    private static final Timestamp COMPLETED = Timestamp.from(Instant.parse("2026-11-01T10:05:00Z"));
    private static final String JOURNEY_ISSUANCE = " (journey_id, request_id, issuance_process_id, created_at) ";

    @Test
    @DisplayName("EC-10 — v6_journeysWithAProcessBeforeIt_keepTheirProcessUnderAFixedRequestIdAndCompletedAt")
    void v6_journeysWithAProcessBeforeIt_keepTheirProcessUnderAFixedRequestIdAndCompletedAt() {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")) {
            postgres.start();
            DriverManagerDataSource dataSource = new DriverManagerDataSource(postgres.getJdbcUrl());
            dataSource.setUsername(postgres.getUsername());
            dataSource.setPassword(postgres.getPassword());
            JdbcClient jdbc = JdbcClient.create(dataSource);
            flyway(dataSource, "5").migrate();
            UUID completedWithSession = journey(jdbc, "completed", "offer_obtained", COMPLETED, UUID.randomUUID());
            session(jdbc, completedWithSession, "a");
            UUID completedClosed = journey(jdbc, "completed", "offer_obtained", COMPLETED, UUID.randomUUID());
            UUID inProgressWithProcess = journey(jdbc, "in_progress", "email_verified", null, UUID.randomUUID());
            session(jdbc, inProgressWithProcess, "b");
            UUID inProgressWithout = journey(jdbc, "in_progress", "identified", null, null);
            session(jdbc, inProgressWithout, "c");
            UUID withdrawn = journey(jdbc, "withdrawn", "identified", COMPLETED, null);
            UUID processOfCompleted = process(jdbc, completedWithSession);

            flyway(dataSource, null).migrate();

            // Their processes now live in journey_issuance, under the fixed request id r1.
            assertThat(jdbc.sql("SELECT request_id FROM " + SCHEMA + ".journey_issuance WHERE journey_id = :id")
                    .param("id", completedWithSession).query(String.class).single()).isEqualTo("r1");
            assertThat(jdbc.sql("SELECT issuance_process_id FROM " + SCHEMA + ".journey_issuance WHERE journey_id = :id")
                    .param("id", completedWithSession).query(UUID.class).single()).isEqualTo(processOfCompleted);
            assertThat(count(jdbc, "journey_issuance WHERE journey_id = :id", completedClosed)).isOne();
            assertThat(count(jdbc, "journey_issuance WHERE journey_id = :id", inProgressWithProcess)).isOne();
            assertThat(count(jdbc, "journey_issuance WHERE journey_id = :id", inProgressWithout)).isZero();
            assertThat(count(jdbc, "journey_issuance WHERE journey_id = :id", withdrawn)).isZero();
            // completed_at takes the old end of the completed ones; one still with its session stays active.
            assertThat(row(jdbc, completedWithSession)).containsEntry("completed_at", COMPLETED)
                    .containsEntry("ended_at", null);
            assertThat(row(jdbc, completedClosed)).containsEntry("completed_at", COMPLETED)
                    .containsEntry("ended_at", COMPLETED);
            assertThat(row(jdbc, inProgressWithout)).containsEntry("completed_at", null).containsEntry("ended_at", null);
            assertThat(row(jdbc, withdrawn)).containsEntry("completed_at", null).containsEntry("ended_at", COMPLETED);
            // The migration only adds: the old column is still there, and so are the sessions.
            assertThat(jdbc.sql("SELECT count(*) FROM information_schema.columns WHERE table_schema = :s "
                            + "AND table_name = 'journey' AND column_name = 'issuance_process_id'")
                    .param("s", SCHEMA).query(Long.class).single()).isOne();
            assertThat(count(jdbc, "journey_session", null)).isEqualTo(3L);
        }
    }

    @Test
    @DisplayName("AC-25 — v6_constraints_ruleOutAnInconsistentJourneyAndADuplicatedProcess")
    void v6_constraints_ruleOutAnInconsistentJourneyAndADuplicatedProcess() {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")) {
            postgres.start();
            DriverManagerDataSource dataSource = new DriverManagerDataSource(postgres.getJdbcUrl());
            dataSource.setUsername(postgres.getUsername());
            dataSource.setPassword(postgres.getPassword());
            JdbcClient jdbc = JdbcClient.create(dataSource);
            flyway(dataSource, null).migrate();

            // A completed journey with no end is valid; a withdrawn one without end, or one in progress with it, is not.
            UUID active = UUID.randomUUID();
            insert(jdbc, active, "completed", "offer_obtained", null, COMPLETED, null);
            UUID withdrawn = UUID.randomUUID();
            assertThatThrownBy(() -> insert(jdbc, withdrawn, "withdrawn", "identified", "cancelled", null, null))
                    .isInstanceOf(DataIntegrityViolationException.class);
            UUID inProgress = UUID.randomUUID();
            assertThatThrownBy(() -> insert(jdbc, inProgress, "in_progress", "identified", null, null, COMPLETED))
                    .isInstanceOf(DataIntegrityViolationException.class);
            UUID completedWithoutEnd = UUID.randomUUID();
            assertThatThrownBy(() -> insert(jdbc, completedWithoutEnd, "completed", "offer_obtained", null, null, null))
                    .isInstanceOf(DataIntegrityViolationException.class);
            UUID process = UUID.randomUUID();
            jdbc.sql("INSERT INTO " + SCHEMA + ".journey_issuance" + JOURNEY_ISSUANCE + "VALUES (:j, 'r1', :p, now())")
                    .param("j", active).param("p", process).update();
            JdbcClient.StatementSpec sameProcessTwice = jdbc.sql("INSERT INTO " + SCHEMA + ".journey_issuance"
                    + JOURNEY_ISSUANCE + "VALUES (:j, 'r2', :p, now())").param("j", active).param("p", process);
            assertThatThrownBy(sameProcessTwice::update).isInstanceOf(DataIntegrityViolationException.class);
            JdbcClient.StatementSpec sameRequestTwice = jdbc.sql("INSERT INTO " + SCHEMA + ".journey_issuance"
                    + JOURNEY_ISSUANCE + "VALUES (:j, 'r1', :p, now())").param("j", active)
                    .param("p", UUID.randomUUID());
            assertThatThrownBy(sameRequestTwice::update).isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    private static UUID journey(JdbcClient jdbc, String state, String step, Timestamp ended, UUID process) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO " + SCHEMA + ".journey (id, state, step, withdrawal_reason, issuance_process_id, created_at, "
                        + "last_activity, ended_at) VALUES (:id, :state, :step, :reason, :process, :created, :created, :ended)")
                .param("id", id).param("state", state).param("step", step)
                .param("reason", "withdrawn".equals(state) ? "cancelled" : null)
                .param("process", process).param("created", CREATED).param("ended", ended).update();
        return id;
    }

    private static void insert(JdbcClient jdbc, UUID id, String state, String step, String reason, Timestamp completed,
                               Timestamp ended) {
        jdbc.sql("INSERT INTO " + SCHEMA + ".journey (id, state, step, withdrawal_reason, created_at, last_activity, "
                        + "completed_at, ended_at) VALUES (:id, :state, :step, :reason, :created, :created, :completed, :ended)")
                .param("id", id).param("state", state).param("step", step).param("reason", reason)
                .param("created", CREATED).param("completed", completed).param("ended", ended).update();
    }

    private static void session(JdbcClient jdbc, UUID journey, String hash) {
        jdbc.sql("INSERT INTO " + SCHEMA + ".journey_session (journey_id, holder_ref) VALUES (:id, :ref)")
                .param("id", journey).param("ref", hash.repeat(64)).update();
    }

    private static UUID process(JdbcClient jdbc, UUID journey) {
        return jdbc.sql("SELECT issuance_process_id FROM " + SCHEMA + ".journey WHERE id = :id")
                .param("id", journey).query(UUID.class).single();
    }

    private static Map<String, Object> row(JdbcClient jdbc, UUID id) {
        return jdbc.sql("SELECT completed_at, ended_at FROM " + SCHEMA + ".journey WHERE id = :id")
                .param("id", id).query().singleRow();
    }

    private static long count(JdbcClient jdbc, String from, UUID id) {
        var statement = jdbc.sql("SELECT count(*) FROM " + SCHEMA + "." + from);
        return (id == null ? statement : statement.param("id", id)).query(Long.class).single();
    }

    private static Flyway flyway(DriverManagerDataSource dataSource, String target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .createSchemas(true)
                .locations(TenantSchemaMigrator.LOCATION);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }
}
