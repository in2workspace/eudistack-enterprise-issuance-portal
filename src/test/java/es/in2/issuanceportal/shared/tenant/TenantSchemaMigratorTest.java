package es.in2.issuanceportal.shared.tenant;

import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantSchemaMigratorTest {

    private static final TenantId ONBOARDED = new TenantId("onboarded");

    @Test
    void ensureSchema_migrated_doesNotMigrateAgain() {
        CountingMigrator migrator = new CountingMigrator(false);

        migrator.ensureSchema(ONBOARDED);
        migrator.ensureSchema(ONBOARDED);

        assertThat(migrator.runs.get()).isEqualTo(1);
    }

    @Test
    void ensureSchema_failedMomentsAgo_failsAtOnceWithoutRetrying() {
        CountingMigrator migrator = new CountingMigrator(true);

        assertThatThrownBy(() -> migrator.ensureSchema(ONBOARDED)).isInstanceOf(FlywayException.class);
        assertThatThrownBy(() -> migrator.ensureSchema(ONBOARDED)).isInstanceOf(FlywayException.class);
        assertThatThrownBy(() -> migrator.ensureSchema(ONBOARDED)).isInstanceOf(FlywayException.class);

        assertThat(migrator.runs.get()).isEqualTo(1);
    }

    @Test
    void ensureSchema_otherTenantAfterAFailure_isMigrated() {
        CountingMigrator migrator = new CountingMigrator(true);
        assertThatThrownBy(() -> migrator.ensureSchema(ONBOARDED)).isInstanceOf(FlywayException.class);
        migrator.failing = false;

        migrator.ensureSchema(new TenantId("another"));

        assertThat(migrator.runs.get()).isEqualTo(2);
    }

    /** Stands in for Flyway: counts the runs, and fails while told to. */
    private static final class CountingMigrator extends TenantSchemaMigrator {

        private final AtomicInteger runs = new AtomicInteger();
        private boolean failing;

        private CountingMigrator(boolean failing) {
            super(null, null, null);
            this.failing = failing;
        }

        @Override
        void migrate(TenantId tenant) {
            runs.incrementAndGet();
            if (failing) {
                throw new FlywayException("migration failed");
            }
            markMigrated(tenant);
        }
    }
}
