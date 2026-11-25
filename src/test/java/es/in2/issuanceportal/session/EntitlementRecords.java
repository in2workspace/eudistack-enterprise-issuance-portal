package es.in2.issuanceportal.session;

import es.in2.issuanceportal.session.api.EntitlementRecord;
import es.in2.issuanceportal.session.api.IdentifierType;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Entitlement sections for the tests of the Journey module. */
public final class EntitlementRecords {

    private EntitlementRecords() {
    }

    /** A check with one available request, {@code r1}, with the data and the preview the test gives it. */
    public static EntitlementRecord available(IdentifierType identifierType, Map<String, Object> claims,
                                              EntitlementRecord.Preview preview, String email, Instant checkedAt) {
        return new EntitlementRecord(identifierType, email, List.of(new EntitlementRecord.Request("r1", "SIM-1",
                EntitlementRecord.Status.AVAILABLE, List.of(), false, null, null, null, null, claims, preview)),
                checkedAt);
    }
}
