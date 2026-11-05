package es.in2.issuanceportal.authenticsource.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PersonalIdentifierTest {

    @Test
    void sameAs_otherType_isFalse() {
        PersonalIdentifier dni = new PersonalIdentifier(IdentifierType.DNI, "280000001");

        assertThat(dni.sameAs(new PersonalIdentifier(IdentifierType.COLEGIADO, "280000001"))).isFalse();
        assertThat(dni.sameAs(null)).isFalse();
    }

    @Test
    void constructor_blankValue_isRejected() {
        assertThatThrownBy(() -> new PersonalIdentifier(IdentifierType.DNI, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toString_identifierAndRecord_hidePersonalData() {
        PersonalIdentifier dni = new PersonalIdentifier(IdentifierType.DNI, "00000001R");
        SourceRecord found = new SourceRecord(List.of(dni), true, Map.of("given_name", "Lucía"));

        assertThat(dni.toString()).contains("dni").doesNotContain("00000001R");
        assertThat(found.toString()).contains("given_name").doesNotContain("00000001R", "Lucía");
    }
}
