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

    private static EntitlementRecord confirmed() {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("personal_administrative_number", "280000001");
        claims.put("medical_board", List.of("Madrid"));
        claims.put("medical_speciality", List.of("Pediatría", "Cardiología"));
        claims.put("legally_entitled", true);
        return new EntitlementRecord(IdentifierType.COLEGIADO, claims,
                new EntitlementRecord.Preview("Atestación DoctorID",
                        List.of(new EntitlementRecord.Field("Nombre", "Lucía Prueba")), null, true),
                CHECKED_AT);
    }

    @Test
    void fromJson_writtenRecord_readsTheSame() {
        EntitlementRecord entitlement = confirmed();

        assertThat(EntitlementRecord.fromJson(entitlement.toJson())).isEqualTo(entitlement);
    }

    @Test
    void toJson_record_usesTheContractNames() {
        assertThat(confirmed().toJson())
                .startsWith("{\"result\":\"confirmed\",\"identifier_type\":\"colegiado\",\"claims\":{")
                .contains("\"legally_entitled\":true", "\"medical_speciality\":[\"Pediatría\",\"Cardiología\"]",
                        "\"preview\":{\"type_name\":\"Atestación DoctorID\"", "\"email_required\":true",
                        "\"checked_at\":\"2026-10-28T10:00:00Z\"")
                .doesNotContain("\"email\":");
    }

    @Test
    void fromJson_withoutSource_hasNoIdentifierTypeNorClaims() {
        EntitlementRecord entitlement = new EntitlementRecord(null, null,
                new EntitlementRecord.Preview("Atestación", List.of(), "lucia@example.org", false), CHECKED_AT);

        EntitlementRecord read = EntitlementRecord.fromJson(entitlement.toJson());

        assertThat(read.identifierTypeIfSearched()).isEmpty();
        assertThat(read.claims()).isEmpty();
        assertThat(read.preview().email()).isEqualTo("lucia@example.org");
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
    void toString_record_hidesPersonalData() {
        assertThat(confirmed().toString())
                .contains("colegiado", "legally_entitled")
                .doesNotContain("280000001", "Madrid", "Pediatría", "Lucía");
        assertThat(confirmed().preview().toString()).doesNotContain("Lucía");
    }

    @Test
    void toString_jsonDocument_hidesPersonalData() {
        EntitlementRecord entitlement = confirmed();
        EntitlementRecord.Document document = new EntitlementRecord.Document("confirmed", "colegiado",
                entitlement.claims(), entitlement.preview(), entitlement.checkedAt());

        assertThat(document.toString()).contains("confirmed").doesNotContain("280000001", "Madrid", "Lucía");
    }
}
