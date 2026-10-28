package es.in2.issuanceportal.authenticsource.domain;

import es.in2.issuanceportal.authenticsource.domain.config.AttributeMapping;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AttestationTranslationTest {

    /** The translation of design.md §3.3 for doctorid.sd.1. */
    static final List<AttributeMapping> DOCTORID = List.of(
            new AttributeMapping("registration_number", "personal_administrative_number", "Número de colegiado"),
            new AttributeMapping("medical_boards", "medical_board", "Colegio"),
            new AttributeMapping("specialities", "medical_speciality", "Especialidad"),
            new AttributeMapping("entitled_to_practise", "legally_entitled", "Habilitado para ejercer"));

    @Test
    void translate_doctorIdRecord_givesTheFourClaims() {
        SourceRecord found = new SourceRecord(List.of(), true, Map.of(
                "registration_number", "280000001",
                "medical_boards", List.of("Madrid"),
                "specialities", List.of("Pediatría", "Cardiología"),
                "entitled_to_practise", true,
                "given_name", "Lucía"));

        assertThat(AttestationTranslation.translate(DOCTORID, found)).containsExactly(
                Map.entry("personal_administrative_number", "280000001"),
                Map.entry("medical_board", List.of("Madrid")),
                Map.entry("medical_speciality", List.of("Pediatría", "Cardiología")),
                Map.entry("legally_entitled", true));
    }

    @Test
    void translate_missingOrNullAttribute_leavesTheClaimOut() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("registration_number", "280000001");
        attributes.put("medical_boards", null);

        assertThat(AttestationTranslation.translate(DOCTORID, new SourceRecord(List.of(), true, attributes)))
                .containsOnlyKeys("personal_administrative_number");
    }
}
