package es.in2.issuanceportal.journey.domain;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IssuancePayloadTest {

    private static final Map<String, String> DOCTORID = Map.of(
            "givenName", "identity.given_name",
            "familyName", "identity.family_name",
            "personal_administrative_number", "authentic_source.personal_administrative_number",
            "medical_board", "authentic_source.medical_board",
            "medical_speciality", "authentic_source.medical_speciality",
            "legally_entitled", "authentic_source.legally_entitled");

    private static final Map<String, String> IDENTITY = Map.of(
            "given_name", "Elena", "family_name", "Ruiz Prieto", "personal_identifier", "00000001R",
            "email", "elena.token@example.test");

    private static final Map<String, Object> CLAIMS = Map.of(
            "personal_administrative_number", "280000001",
            "medical_board", List.of("Colegio de Medicos de Prueba"),
            "medical_speciality", List.of("Cardiology"),
            "legally_entitled", true,
            "contact_email", "source@example.test");

    @Test
    void build_doctorIdPlacement_takesIdentityAndSourceFieldsAsPlaced() {
        Map<String, Object> payload = IssuancePayload.build(DOCTORID, IDENTITY, CLAIMS);

        assertThat(payload).containsOnlyKeys("givenName", "familyName", "personal_administrative_number",
                "medical_board", "medical_speciality", "legally_entitled");
        assertThat(payload).containsEntry("givenName", "Elena")
                .containsEntry("familyName", "Ruiz Prieto")
                .containsEntry("personal_administrative_number", "280000001")
                .containsEntry("medical_board", List.of("Colegio de Medicos de Prueba"))
                .containsEntry("legally_entitled", true);
    }

    @Test
    void build_documentEmailAndCountryNotPlaced_areNotInThePayload() {
        Map<String, Object> payload = IssuancePayload.build(DOCTORID, IDENTITY, CLAIMS);

        assertThat(payload.toString()).doesNotContain("00000001R", "token@example.test", "source@example.test");
    }

    @Test
    void build_placementSaysSo_includesTheIdentifierAndTheEmail() {
        Map<String, Object> payload = IssuancePayload.build(Map.of(
                "documentNumber", "identity.personal_identifier", "email", "identity.email"), IDENTITY, CLAIMS);

        assertThat(payload).containsEntry("documentNumber", "00000001R")
                .containsEntry("email", "elena.token@example.test");
    }

    @Test
    void build_emptyPlacement_isIncomplete() {
        assertThatThrownBy(() -> IssuancePayload.build(Map.of(), IDENTITY, CLAIMS))
                .isInstanceOfSatisfying(IncompletePlacementException.class,
                        e -> assertThat(e.field()).isEqualTo("data_placement"));
    }

    @Test
    void build_sourceClaimMissing_namesTheAttestationFieldOnly() {
        Map<String, Object> withoutBoard = new LinkedHashMap<>(CLAIMS);
        withoutBoard.remove("medical_board");

        assertThatThrownBy(() -> IssuancePayload.build(DOCTORID, IDENTITY, withoutBoard))
                .isInstanceOfSatisfying(IncompletePlacementException.class,
                        e -> assertThat(e.field()).isEqualTo("medical_board"));
    }

    @Test
    void build_identityFieldWithoutValue_isIncomplete() {
        Map<String, String> noEmail = Map.of("given_name", "Elena", "family_name", "Ruiz");

        assertThatThrownBy(() -> IssuancePayload.build(Map.of("email", "identity.email"), noEmail, CLAIMS))
                .isInstanceOf(IncompletePlacementException.class);
    }

    @Test
    void build_unknownOriginOrShape_isIncomplete() {
        for (String origin : new String[] {"wallet.given_name", "given_name", "identity.", ".given_name", ""}) {
            assertThatThrownBy(() -> IssuancePayload.build(Map.of("givenName", origin), IDENTITY, CLAIMS))
                    .as(origin)
                    .isInstanceOf(IncompletePlacementException.class);
        }
    }
}
