package es.in2.issuanceportal.journey;

import es.in2.issuanceportal.journey.application.port.JourneyIssuanceRepository;
import es.in2.issuanceportal.journey.domain.JourneyIssuance;
import es.in2.issuanceportal.shared.tenant.TenantId;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** The processes of the journeys in memory, with the same uniqueness as the table. */
public class InMemoryJourneyIssuanceRepository implements JourneyIssuanceRepository {

    private record Row(TenantId tenant, JourneyIssuance issuance) {
    }

    private final List<Row> rows = new CopyOnWriteArrayList<>();

    @Override
    public synchronized void insert(TenantId tenant, JourneyIssuance issuance) {
        boolean duplicate = rows.stream().anyMatch(row -> row.issuance().issuanceProcessId()
                .equals(issuance.issuanceProcessId())
                || row.issuance().journeyId().equals(issuance.journeyId())
                && row.issuance().requestId().equals(issuance.requestId()));
        if (duplicate) {
            throw new org.springframework.dao.DuplicateKeyException("journey_issuance");
        }
        rows.add(new Row(tenant, issuance));
    }

    @Override
    public synchronized boolean markSaved(TenantId tenant, UUID journeyId, String requestId, Instant savedAt) {
        for (Row row : rows) {
            JourneyIssuance issuance = row.issuance();
            if (row.tenant().equals(tenant) && issuance.journeyId().equals(journeyId)
                    && issuance.requestId().equals(requestId) && !issuance.isSaved()) {
                rows.set(rows.indexOf(row), new Row(tenant, new JourneyIssuance(journeyId, requestId,
                        issuance.issuanceProcessId(), issuance.createdAt(), savedAt)));
                return true;
            }
        }
        return false;
    }

    @Override
    public List<JourneyIssuance> findByJourney(TenantId tenant, UUID journeyId) {
        return rows.stream().filter(row -> row.tenant().equals(tenant) && row.issuance().journeyId().equals(journeyId))
                .map(Row::issuance).sorted(Comparator.comparing(JourneyIssuance::createdAt)).toList();
    }
}
