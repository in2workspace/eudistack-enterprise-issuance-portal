package es.in2.issuanceportal.authenticsource.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PersonalIdentifierTest {

    @Test
    void sameAs_otherType_isFalse() {
        PersonalIdentifier dni = new PersonalIdentifier(IdentifierType.PERSONAL_ID, "280000001");

        assertThat(dni.sameAs(new PersonalIdentifier(IdentifierType.COLEGIADO, "280000001"))).isFalse();
        assertThat(dni.sameAs(null)).isFalse();
    }

    @Test
    void constructor_blankValue_isRejected() {
        assertThatThrownBy(() -> new PersonalIdentifier(IdentifierType.PERSONAL_ID, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toString_identifier_hidesPersonalData() {
        PersonalIdentifier dni = new PersonalIdentifier(IdentifierType.PERSONAL_ID, "00000001R");

        assertThat(dni.toString()).contains("personal_id").doesNotContain("00000001R");
    }
}
