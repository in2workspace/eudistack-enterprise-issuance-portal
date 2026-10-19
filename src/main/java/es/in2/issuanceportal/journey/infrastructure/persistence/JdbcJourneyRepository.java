package es.in2.issuanceportal.journey.infrastructure.persistence;

import es.in2.issuanceportal.journey.application.port.JourneyRepository;
import es.in2.issuanceportal.journey.domain.Journey;
import es.in2.issuanceportal.journey.domain.JourneyState;
import es.in2.issuanceportal.journey.domain.JourneyStep;
import es.in2.issuanceportal.journey.domain.WithdrawalReason;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@code journey} in the tenant's schema. */
@Component
class JdbcJourneyRepository implements JourneyRepository {

    private static final String COLUMNS =
            "id, state, step, withdrawal_reason, identification_method, issuance_process_ids, created_at, last_activity, ended_at";

    private static final RowMapper<Journey> ROW = (rs, row) -> {
        String reason = rs.getString("withdrawal_reason");
        Timestamp endedAt = rs.getTimestamp("ended_at");
        Array processIds = rs.getArray("issuance_process_ids");
        return Journey.restore(
                rs.getObject("id", UUID.class),
                JourneyState.fromCode(rs.getString("state")),
                JourneyStep.fromCode(rs.getString("step")),
                reason == null ? null : WithdrawalReason.fromCode(reason),
                rs.getString("identification_method"),
                processIds == null ? List.of() : Arrays.asList((String[]) processIds.getArray()),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("last_activity").toInstant(),
                endedAt == null ? null : endedAt.toInstant());
    };

    private final JdbcClient jdbc;

    JdbcJourneyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(TenantId tenant, Journey journey) {
        jdbc.sql("INSERT INTO " + table(tenant) + " (id, state, step, created_at, last_activity) "
                        + "VALUES (:id, :state, :step, :createdAt, :lastActivity)")
                .param("id", journey.id())
                .param("state", journey.state().code())
                .param("step", journey.step().code())
                .param("createdAt", Timestamp.from(journey.createdAt()))
                .param("lastActivity", Timestamp.from(journey.lastActivity()))
                .update();
    }

    @Override
    public boolean update(TenantId tenant, Journey journey, JourneyState readInState) {
        return jdbc.sql("UPDATE " + table(tenant) + " SET state = :state, step = :step, withdrawal_reason = :reason, "
                        + "last_activity = :lastActivity, ended_at = :endedAt WHERE id = :id AND state = :readInState")
                .param("state", journey.state().code())
                .param("step", journey.step().code())
                .param("reason", journey.withdrawalReason().map(WithdrawalReason::code).orElse(null))
                .param("lastActivity", Timestamp.from(journey.lastActivity()))
                .param("endedAt", journey.endedAt().map(Timestamp::from).orElse(null))
                .param("id", journey.id())
                .param("readInState", readInState.code())
                .update() == 1;
    }

    @Override
    public Optional<Journey> findById(TenantId tenant, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM " + table(tenant) + " WHERE id = :id")
                .param("id", id)
                .query(ROW)
                .optional();
    }

    @Override
    public List<Journey> findInProgressInactiveSince(TenantId tenant, Instant cutoff, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM " + table(tenant)
                        + " WHERE state = :state AND last_activity <= :cutoff ORDER BY last_activity LIMIT :limit")
                .param("state", JourneyState.IN_PROGRESS.code())
                .param("cutoff", Timestamp.from(cutoff))
                .param("limit", limit)
                .query(ROW)
                .list();
    }

    private static String table(TenantId tenant) {
        return tenant.schema() + ".journey";
    }
}
