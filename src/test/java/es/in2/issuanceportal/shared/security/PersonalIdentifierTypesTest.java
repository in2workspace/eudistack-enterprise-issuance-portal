package es.in2.issuanceportal.shared.security;

import es.in2.issuanceportal.session.api.IdentifierType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PersonalIdentifierTypesTest {

    private static final Map<String, String> MAPPING = Map.of("doctorid.sd.1", "colegiado");

    @Test
    void resolve_noCredentialTypeAndHardwareKey_isPersonalId() {
        assertThat(PersonalIdentifierTypes.resolve(null, List.of("hwk"), MAPPING)).contains(IdentifierType.PERSONAL_ID);
    }

    @Test
    void resolve_blankCredentialTypeAndSoftwareKey_isPersonalId() {
        assertThat(PersonalIdentifierTypes.resolve(" ", List.of("pwd", "swk"), MAPPING))
                .contains(IdentifierType.PERSONAL_ID);
    }

    @Test
    void resolve_credentialTypeMapped_isTheMappedType() {
        assertThat(PersonalIdentifierTypes.resolve("doctorid.sd.1", List.of("hwk"), MAPPING))
                .contains(IdentifierType.COLEGIADO);
    }

    @Test
    void resolve_credentialTypeNotMapped_isEmptyEvenWithCertificateMethod() {
        assertThat(PersonalIdentifierTypes.resolve("other.sd.1", List.of("hwk"), MAPPING)).isEmpty();
    }

    @Test
    void resolve_mappedToAnUnknownType_isEmpty() {
        assertThat(PersonalIdentifierTypes.resolve("doctorid.sd.1", List.of(), Map.of("doctorid.sd.1", "nif")))
                .isEmpty();
    }

    @Test
    void resolve_noCredentialTypeAndOtherMethod_isEmpty() {
        assertThat(PersonalIdentifierTypes.resolve(null, List.of("pwd"), MAPPING)).isEmpty();
        assertThat(PersonalIdentifierTypes.resolve(null, List.of(), MAPPING)).isEmpty();
    }
}
