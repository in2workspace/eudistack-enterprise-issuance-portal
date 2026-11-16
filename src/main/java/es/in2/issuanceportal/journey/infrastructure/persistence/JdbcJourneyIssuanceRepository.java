package es.in2.issuanceportal.journey.infrastructure.persistence;

import es.in2.issuanceportal.journey.application.port.JourneyIssuanceRepository;
import es.in2.issuanceportal.journey.domain.JourneyIssuance;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/** {@code journey_issuance} in the tenant's schema. */
@Repository
class JdbcJourneyIssuanceRepository implements JourneyIssuanceRepository {

    private static final RowMapper<JourneyIssuance> ROW = (rs, row) -> {
        Timestamp savedAt = rs.getTimestamp("saved_at");
        return new JourneyIssuance(rs.getObject("journey_id", UUID.class), rs.getString("request_id"),
                rs.getObject("issuance_process_id", UUID.class), rs.getTimestamp("created_at").toInstant(),
                savedAt == null ? null : savedAt.toInstant());
    };

    private final JdbcClient jdbc;

    JdbcJourneyIssuanceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(TenantId tenant, JourneyIssuance issuance) {
        jdbc.sql("INSERT INTO " + table(tenant) + " (journey_id, request_id, issuance_process_id, created_at, saved_at) "
                        + "VALUES (:journey, :request, :process, :createdAt, :savedAt)")
                .param("journey", issuance.journeyId())
                .param("request", issuance.requestId())
                .param("process", issuance.issuanceProcessId())
                .param("createdAt", Timestamp.from(issuance.createdAt()))
                .param("savedAt", issuance.savedAtIfSaved().map(Timestamp::from).orElse(null))
                .update();
    }

    @Override
    public List<JourneyIssuance> findByJourney(TenantId tenant, UUID journeyId) {
        return jdbc.sql("SELECT journey_id, request_id, issuance_process_id, created_at, saved_at FROM " + table(tenant)
                        + " WHERE journey_id = :journey ORDER BY created_at, request_id")
                .param("journey", journeyId)
                .query(ROW)
                .list();
    }

    private static String table(TenantId tenant) {
        return tenant.schema() + ".journey_issuance";
    }
}
