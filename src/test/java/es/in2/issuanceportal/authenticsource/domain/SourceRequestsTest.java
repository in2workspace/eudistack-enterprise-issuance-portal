package es.in2.issuanceportal.authenticsource.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceRequestsTest {

    private static SourceRequest request(RequestStatus status) {
        return new SourceRequest("SIM-0001-A", status, List.of(MismatchedField.GIVEN_NAME), true, "Secretaría",
                Instant.parse("2026-10-01T09:00:00Z"), "280000001", "Colegio de Médicos de Prueba",
                Map.of("registration_number", "280000001"));
    }

    @Test
    void requests_aSourceRequestUnderReview_isRejected() {
        SourceRequest underReview = request(RequestStatus.UNDER_REVIEW);

        assertThatThrownBy(() -> SourceRequests.of(underReview)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sourceRequest_withoutReference_isRejected() {
        assertThatThrownBy(() -> new SourceRequest(" ", RequestStatus.AVAILABLE, null, false, null, null, null, null,
                null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sourceRequest_withStatus_changesOnlyTheStatus() {
        SourceRequest underReview = request(RequestStatus.AVAILABLE).withStatus(RequestStatus.UNDER_REVIEW);

        assertThat(underReview).isEqualTo(request(RequestStatus.UNDER_REVIEW));
    }

    @Test
    void toString_holderAndRequest_hidePersonalData() {
        Holder holder = new Holder("Lucía", "Prueba Activa", new PersonalIdentifier(IdentifierType.PERSONAL_ID,
                "00000001R"));

        assertThat(holder.toString()).contains("personal_id").doesNotContain("Lucía", "Prueba", "00000001R");
        assertThat(request(RequestStatus.AVAILABLE).toString())
                .contains("available", "registration_number")
                .doesNotContain("SIM-0001-A", "280000001", "Secretaría", "Colegio");
    }
}
