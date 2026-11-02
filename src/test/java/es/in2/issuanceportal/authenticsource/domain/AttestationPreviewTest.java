package es.in2.issuanceportal.authenticsource.domain;

import es.in2.issuanceportal.authenticsource.domain.config.AttributeMapping;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AttestationPreviewTest {

    private static Map<String, Object> claims() {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("personal_administrative_number", "280000001");
        claims.put("medical_board", List.of("Madrid", "Toledo"));
        claims.put("medical_speciality", List.of("Pediatría"));
        claims.put("legally_entitled", true);
        return claims;
    }

    @Test
    @DisplayName("AC-03 — of_confirmedDoctorId_showsNameThenLabelledClaims")
    void of_confirmedDoctorId_showsNameThenLabelledClaims() {
        AttestationPreview preview = AttestationPreview.of("DoctorID", "Lucía", "Prueba Sintética",
                AttestationTranslationTest.DOCTORID, claims(), null, null);

        assertThat(preview.typeName()).isEqualTo("DoctorID");
        assertThat(preview.fields()).containsExactly(
                new AttestationPreview.Field("Nombre y apellidos", "Lucía Prueba Sintética"),
                new AttestationPreview.Field("Número de colegiado", "280000001"),
                new AttestationPreview.Field("Colegio", "Madrid, Toledo"),
                new AttestationPreview.Field("Especialidad", "Pediatría"),
                new AttestationPreview.Field("Habilitado para ejercer", "Sí"));
    }

    @Test
    void of_claimWithoutLabel_isNotShown() {
        List<AttributeMapping> hidden = List.of(
                new AttributeMapping("registration_number", "personal_administrative_number", null));

        AttestationPreview preview = AttestationPreview.of("DoctorID", "Lucía", "Prueba", hidden, claims(), null, null);

        assertThat(preview.fields()).extracting(AttestationPreview.Field::value)
                .isNotEmpty()
                .doesNotContain("280000001");
    }

    @Test
    @DisplayName("AC-14 — of_noEmailAnywhere_requiresOne")
    void of_noEmailAnywhere_requiresOne() {
        AttestationPreview preview = AttestationPreview.of("DoctorID", "Lucía", "Prueba", List.of(), Map.of(), " ", null);

        assertThat(preview.email()).isNull();
        assertThat(preview.emailRequired()).isTrue();
    }

    @Test
    @DisplayName("AC-14 — of_emailOfIdentityAndSource_prefersTheIdentity")
    void of_emailOfIdentityAndSource_prefersTheIdentity() {
        AttestationPreview preview = AttestationPreview.of("DoctorID", "Lucía", "Prueba", List.of(), Map.of(),
                "token@example.org", "fuente@example.org");

        assertThat(preview.email()).isEqualTo("token@example.org");
        assertThat(preview.emailRequired()).isFalse();
    }

    @Test
    @DisplayName("AC-14 — of_emailOnlyFromSource_usesIt")
    void of_emailOnlyFromSource_usesIt() {
        AttestationPreview preview = AttestationPreview.of("DoctorID", "Lucía", "Prueba", List.of(), Map.of(), null,
                "fuente@example.org");

        assertThat(preview.email()).isEqualTo("fuente@example.org");
        assertThat(preview.emailRequired()).isFalse();
    }

    @Test
    @DisplayName("AC-10 — of_withoutSource_showsOnlyTheName")
    void of_withoutSource_showsOnlyTheName() {
        AttestationPreview preview = AttestationPreview.of("employee.sd.1", "Lucía", "Prueba", List.of(), Map.of(),
                null, null);

        assertThat(preview.fields()).containsExactly(new AttestationPreview.Field("Nombre y apellidos", "Lucía Prueba"));
    }

    @Test
    void toString_preview_hidesPersonalData() {
        AttestationPreview preview = AttestationPreview.of("DoctorID", "Lucía", "Prueba",
                AttestationTranslationTest.DOCTORID, claims(), "lucia@example.org", null);

        assertThat(preview.toString()).doesNotContain("Lucía", "280000001", "lucia@example.org");
        assertThat(preview.fields().getFirst().toString()).doesNotContain("Lucía");
    }
}
