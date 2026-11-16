package es.in2.issuanceportal.session.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntitlementRecordTest {

    private static final Instant CHECKED_AT = Instant.parse("2026-10-28T10:00:00Z");

    private static EntitlementRecord.Request available(String id, String sourceRef) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("personal_administrative_number", "280000001");
        claims.put("medical_board", List.of("Madrid"));
        claims.put("medical_speciality", List.of("Pediatría", "Cardiología"));
        claims.put("legally_entitled", true);
        return new EntitlementRecord.Request(id, sourceRef, EntitlementRecord.Status.AVAILABLE, List.of(), true,
                "Secretaría", Instant.parse("2026-10-01T09:00:00Z"), "280000001", "Colegio de Prueba", claims,
                new EntitlementRecord.Preview("Atestación DoctorID",
                        List.of(new EntitlementRecord.Field("Nombre", "Lucía Prueba"))));
    }

    private static EntitlementRecord.Request mismatch(String id, String sourceRef) {
        return new EntitlementRecord.Request(id, sourceRef, EntitlementRecord.Status.IDENTITY_MISMATCH,
                List.of("given_name"), false, null, null, "280000002", null, null, null);
    }

    private static EntitlementRecord confirmed() {
        return new EntitlementRecord(IdentifierType.COLEGIADO, "lucia@example.org",
                List.of(mismatch("r1", "SIM-B"), available("r2", "SIM-A")), CHECKED_AT);
    }

    @Test
    void fromJson_writtenRecord_readsTheSame() {
        EntitlementRecord entitlement = confirmed();

        assertThat(EntitlementRecord.fromJson(entitlement.toJson())).isEqualTo(entitlement);
    }

    @Test
    void toJson_record_usesTheContractNames() {
        assertThat(confirmed().toJson())
                .startsWith("{\"result\":\"confirmed\",\"identifier_type\":\"colegiado\","
                        + "\"email\":\"lucia@example.org\",\"requests\":[{\"id\":\"r1\",\"source_ref\":\"SIM-B\","
                        + "\"status\":\"identity_mismatch\",\"mismatched_fields\":[\"given_name\"]")
                .contains("\"legally_entitled\":true", "\"medical_speciality\":[\"Pediatría\",\"Cardiología\"]",
                        "\"preview\":{\"type_name\":\"Atestación DoctorID\"", "\"renewal\":true",
                        "\"requested_at\":\"2026-10-01T09:00:00Z\"", "\"checked_at\":\"2026-10-28T10:00:00Z\"");
    }

    @Test
    void fromJson_requestsKeepTheOrderOfTheSource() {
        assertThat(EntitlementRecord.fromJson(confirmed().toJson()).requests()).extracting(EntitlementRecord.Request::id)
                .containsExactly("r1", "r2");
    }

    @Test
    void firstAvailable_severalRequests_isTheFirstAvailableOne() {
        assertThat(confirmed().firstAvailable()).get().extracting(EntitlementRecord.Request::id).isEqualTo("r2");
        assertThat(confirmed().request("r1")).isPresent();
        assertThat(confirmed().request("r9")).isEmpty();
    }

    @Test
    void fromJson_withoutSource_hasNoIdentifierTypeNorClaims() {
        EntitlementRecord entitlement = new EntitlementRecord(null, "lucia@example.org",
                List.of(new EntitlementRecord.Request("r1", "no-source", EntitlementRecord.Status.AVAILABLE, List.of(),
                        false, null, null, null, null, null,
                        new EntitlementRecord.Preview("Atestación", List.of()))), CHECKED_AT);

        EntitlementRecord read = EntitlementRecord.fromJson(entitlement.toJson());

        assertThat(read.identifierTypeIfSearched()).isEmpty();
        assertThat(read.requests().getFirst().claims()).isEmpty();
        assertThat(read.emailIfKnown()).contains("lucia@example.org");
    }

    @Test
    void fromJson_notConfirmed_namesTheResult() {
        assertThatThrownBy(() -> EntitlementRecord.fromJson("{\"result\":\"not_confirmed\"}"))
                .isInstanceOfSatisfying(InvalidSectionException.class, e -> assertThat(e.field()).isEqualTo("result"));
    }

    @Test
    void fromJson_unknownIdentifierType_namesTheField() {
        String json = confirmed().toJson().replace("\"colegiado\"", "\"nif\"");

        assertThatThrownBy(() -> EntitlementRecord.fromJson(json))
                .isInstanceOfSatisfying(InvalidSectionException.class,
                        e -> assertThat(e.field()).isEqualTo("identifier_type"));
    }

    @Test
    void fromJson_sectionInThePreviousShape_namesRequests() {
        String previous = "{\"result\":\"confirmed\",\"identifier_type\":\"colegiado\",\"claims\":{\"a\":1},"
                + "\"preview\":{\"type_name\":\"DoctorID\",\"fields\":[],\"email_required\":true},"
                + "\"checked_at\":\"2026-10-28T10:00:00Z\"}";

        assertThatThrownBy(() -> EntitlementRecord.fromJson(previous))
                .isInstanceOfSatisfying(InvalidSectionException.class, e -> assertThat(e.field()).isEqualTo("requests"));
    }

    @Test
    void fromJson_emptyListOfRequests_namesRequests() {
        String json = new EntitlementRecord(null, null, List.of(), CHECKED_AT).toJson();

        assertThatThrownBy(() -> EntitlementRecord.fromJson(json))
                .isInstanceOfSatisfying(InvalidSectionException.class, e -> assertThat(e.field()).isEqualTo("requests"));
    }

    @Test
    void fromJson_unknownStatus_namesTheRequestsField() {
        String json = confirmed().toJson().replace("identity_mismatch", "gone_wrong");

        assertThatThrownBy(() -> EntitlementRecord.fromJson(json))
                .isInstanceOfSatisfying(InvalidSectionException.class,
                        e -> assertThat(e.field()).isEqualTo("requests[0].status"));
    }

    @Test
    void fromJson_idOfMoreThanSixtyFourCharacters_namesTheField() {
        String json = confirmed().toJson().replace("\"r1\"", "\"" + "r".repeat(65) + "\"");

        assertThatThrownBy(() -> EntitlementRecord.fromJson(json))
                .isInstanceOfSatisfying(InvalidSectionException.class,
                        e -> assertThat(e.field()).isEqualTo("requests[0].id"));
    }

    @Test
    void fromJson_availableRequestWithoutPreview_namesTheField() {
        String json = new EntitlementRecord(null, null, List.of(new EntitlementRecord.Request("r1", "SIM-A",
                EntitlementRecord.Status.AVAILABLE, List.of(), false, null, null, null, null, null, null)),
                CHECKED_AT).toJson();

        assertThatThrownBy(() -> EntitlementRecord.fromJson(json))
                .isInstanceOfSatisfying(InvalidSectionException.class,
                        e -> assertThat(e.field()).isEqualTo("requests[0].preview"));
    }

    @Test
    void toString_record_hidesPersonalData() {
        assertThat(confirmed().toString())
                .contains("colegiado")
                .doesNotContain("280000001", "Madrid", "Pediatría", "Lucía", "SIM-A", "lucia@example.org");
        assertThat(confirmed().requests().get(1).toString())
                .contains("r2", "available", "legally_entitled")
                .doesNotContain("280000001", "Madrid", "Pediatría", "Lucía", "SIM-A", "Secretaría");
        assertThat(confirmed().requests().get(1).preview().toString()).doesNotContain("Lucía");
    }

    @Test
    void toString_jsonDocuments_hidePersonalData() {
        EntitlementRecord entitlement = confirmed();
        EntitlementRecord.Document document = new EntitlementRecord.Document("confirmed", "colegiado",
                "lucia@example.org", null, entitlement.checkedAt());
        EntitlementRecord.RequestDocument request = new EntitlementRecord.RequestDocument("r1", "SIM-A", "available",
                null, false, "Secretaría", null, "280000001", null, Map.of("a", "Madrid"), null);

        assertThat(document.toString()).contains("confirmed").doesNotContain("lucia@example.org");
        assertThat(request.toString()).contains("r1").doesNotContain("SIM-A", "280000001", "Madrid", "Secretaría");
    }
}
