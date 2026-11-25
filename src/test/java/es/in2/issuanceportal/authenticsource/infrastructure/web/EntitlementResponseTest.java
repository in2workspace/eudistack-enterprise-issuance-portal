package es.in2.issuanceportal.authenticsource.infrastructure.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.issuanceportal.authenticsource.application.EntitlementService;
import es.in2.issuanceportal.authenticsource.domain.AttestationPreview;
import es.in2.issuanceportal.authenticsource.domain.MismatchedField;
import es.in2.issuanceportal.authenticsource.domain.NotConfirmedReason;
import es.in2.issuanceportal.authenticsource.domain.RequestStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class EntitlementResponseTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static EntitlementService.CheckedRequest available() {
        return new EntitlementService.CheckedRequest("r1", RequestStatus.AVAILABLE, List.of(), true, "Secretaría",
                Instant.parse("2026-10-01T09:00:00Z"), "280000001", "Colegio de Prueba",
                Optional.of(new AttestationPreview("DoctorID", List.of(
                        new AttestationPreview.Field("Nombre y apellidos", "Lucía Prueba"),
                        new AttestationPreview.Field("Número de colegiado", "280000001")), "lucia@example.org",
                        false)));
    }

    private static EntitlementService.CheckedRequest mismatch() {
        return new EntitlementService.CheckedRequest("r2", RequestStatus.IDENTITY_MISMATCH,
                List.of(MismatchedField.GIVEN_NAME, MismatchedField.FAMILY_NAMES), false, null, null, null, null,
                Optional.empty());
    }

    @Test
    void toString_confirmed_hidesPersonalData() {
        EntitlementResponse response = EntitlementResponse.of(
                new EntitlementService.Outcome.Confirmed(List.of(available(), mismatch())));

        assertThat(response.toString()).contains("confirmed").doesNotContain("Lucía", "280000001", "lucia@example.org");
        assertThat(response.requests().getFirst().toString()).doesNotContain("Lucía", "280000001", "Secretaría");
        assertThat(response.requests().getFirst().attestation().toString()).doesNotContain("Lucía", "280000001");
        assertThat(response.requests().getFirst().attestation().fields().getFirst().toString())
                .doesNotContain("Lucía");
    }

    @Test
    void toJson_confirmed_usesTheContractNamesWithoutEmailNorSourceReference() throws Exception {
        EntitlementResponse response = EntitlementResponse.of(
                new EntitlementService.Outcome.Confirmed(List.of(available(), mismatch())));

        JsonNode json = JSON.readTree(JSON.writeValueAsString(response));

        assertThat(json.path("result").asText()).isEqualTo("confirmed");
        assertThat(json.has("reason")).isFalse();
        JsonNode first = json.path("requests").get(0);
        assertThat(first.path("id").asText()).isEqualTo("r1");
        assertThat(first.path("status").asText()).isEqualTo("available");
        assertThat(first.path("renewal").asBoolean()).isTrue();
        assertThat(first.path("requested_by").asText()).isEqualTo("Secretaría");
        assertThat(first.path("requested_at").asText()).isEqualTo("2026-10-01T09:00:00Z");
        assertThat(first.path("display_identifier").asText()).isEqualTo("280000001");
        assertThat(first.path("organization").asText()).isEqualTo("Colegio de Prueba");
        assertThat(first.has("mismatched_fields")).isFalse();
        assertThat(first.path("attestation").path("type_name").asText()).isEqualTo("DoctorID");
        assertThat(first.path("attestation").path("fields")).hasSize(2);
        assertThat(first.path("attestation").has("email")).isFalse();
        assertThat(first.path("attestation").has("email_required")).isFalse();
        JsonNode second = json.path("requests").get(1);
        assertThat(second.path("status").asText()).isEqualTo("identity_mismatch");
        assertThat(second.path("mismatched_fields")).extracting(JsonNode::asText)
                .containsExactly("given_name", "family_names");
        assertThat(second.has("attestation")).isFalse();
        assertThat(second.has("requested_by")).isFalse();
        assertThat(JSON.writeValueAsString(response)).doesNotContain("source_ref", "sourceRef");
    }

    @Test
    void of_notConfirmedWithReason_carriesTheReasonAndNoRequests() throws Exception {
        EntitlementResponse response = EntitlementResponse.of(
                new EntitlementService.Outcome.NotConfirmed(Optional.of(NotConfirmedReason.NO_REQUESTS), List.of()));

        assertThat(response.reason()).isEqualTo("no_requests");
        JsonNode json = JSON.readTree(JSON.writeValueAsString(response));
        assertThat(json.path("result").asText()).isEqualTo("not_confirmed");
        assertThat(json.path("requests").isArray()).isTrue();
        assertThat(json.path("requests")).isEmpty();
    }

    @Test
    void of_notConfirmedWithRequests_hasNoReason() throws Exception {
        EntitlementResponse response = EntitlementResponse.of(
                new EntitlementService.Outcome.NotConfirmed(Optional.empty(), List.of(mismatch())));

        assertThat(response.reason()).isNull();
        assertThat(response.result()).isEqualTo("not_confirmed");
        assertThat(JSON.readTree(JSON.writeValueAsString(response)).has("reason")).isFalse();
        assertThat(response.requests()).singleElement().satisfies(request -> {
            assertThat(request.status()).isEqualTo("identity_mismatch");
            assertThat(request.attestation()).isNull();
        });
    }
}
