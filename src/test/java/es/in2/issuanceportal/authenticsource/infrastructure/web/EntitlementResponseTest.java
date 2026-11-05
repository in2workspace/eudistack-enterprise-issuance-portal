package es.in2.issuanceportal.authenticsource.infrastructure.web;

import es.in2.issuanceportal.authenticsource.application.EntitlementService;
import es.in2.issuanceportal.authenticsource.domain.AttestationPreview;
import es.in2.issuanceportal.authenticsource.domain.NotConfirmedReason;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EntitlementResponseTest {

    @Test
    void toString_confirmed_hidesPersonalData() {
        EntitlementResponse response = EntitlementResponse.of(new EntitlementService.Outcome.Confirmed(
                new AttestationPreview("DoctorID", List.of(new AttestationPreview.Field("Nombre y apellidos",
                        "Lucía Prueba"), new AttestationPreview.Field("Número de colegiado", "280000001")),
                        "lucia@example.org", false)));

        assertThat(response.toString())
                .contains("confirmed", "DoctorID")
                .doesNotContain("Lucía", "280000001", "lucia@example.org");
        assertThat(response.attestation().fields().getFirst().toString()).doesNotContain("Lucía");
    }

    @Test
    void of_notConfirmed_carriesTheReasonOnly() {
        EntitlementResponse response = EntitlementResponse.of(
                new EntitlementService.Outcome.NotConfirmed(NotConfirmedReason.IDENTIFIER_MISMATCH));

        assertThat(response.reason()).isEqualTo("identifier_mismatch");
        assertThat(response.attestation()).isNull();
    }
}
