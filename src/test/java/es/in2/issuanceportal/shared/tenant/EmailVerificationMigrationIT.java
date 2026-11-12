package es.in2.issuanceportal.shared.tenant;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** V4 of the tenant schema against PostgreSQL, applied over data written by the code that came before it. */
class EmailVerificationMigrationIT {

    private static final String SCHEMA = "legacy_issuance_portal";

    @Test
    @DisplayName("NFR-2 — v4_journeySessionWithEmailBeforeIt_hasNoEmailAfterIt")
    void v4_journeySessionWithEmailBeforeIt_hasNoEmailAfterIt() {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")) {
            postgres.start();
            DriverManagerDataSource dataSource = new DriverManagerDataSource(postgres.getJdbcUrl());
            dataSource.setUsername(postgres.getUsername());
            dataSource.setPassword(postgres.getPassword());
            JdbcClient jdbc = JdbcClient.create(dataSource);

            flyway(dataSource, "3").migrate();
            UUID journey = UUID.randomUUID();
            Timestamp now = Timestamp.from(Instant.now());
            jdbc.sql("INSERT INTO " + SCHEMA + ".journey (id, state, step, created_at, last_activity) "
                            + "VALUES (:id, 'in_progress', 'entitlement_confirmed', :now, :now)")
                    .param("id", journey).param("now", now).update();
            jdbc.sql("INSERT INTO " + SCHEMA + ".journey_session (session_id_hash, journey_id, email) "
                            + "VALUES (:hash, :id, :email)")
                    .param("hash", "a".repeat(64)).param("id", journey).param("email", new byte[] {1, 2, 3}).update();

            flyway(dataSource, null).migrate();

            assertThat(jdbc.sql("SELECT count(*) FROM " + SCHEMA + ".journey_session WHERE journey_id = :id")
                    .param("id", journey).query(Long.class).single()).isEqualTo(1L);
            assertThat(jdbc.sql("SELECT count(*) FROM " + SCHEMA
                            + ".journey_session WHERE journey_id = :id AND email IS NULL")
                    .param("id", journey).query(Long.class).single()).isEqualTo(1L);
        }
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
