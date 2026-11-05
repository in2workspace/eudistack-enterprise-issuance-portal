package es.in2.issuanceportal.authenticsource.domain.config;

import es.in2.issuanceportal.authenticsource.domain.IdentifierType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticSourceConfigTest {

    private static final String DOCTORID = "doctorid.sd.1";
    private static final SourceSettings SOURCE = new SourceSettings("DoctorID", "simulated", null, null, null,
            Set.of(IdentifierType.DNI), List.of(), null, Map.of());

    @Test
    void constructor_sourceOfTheAttestationType_isTheSource() {
        assertThat(new AuthenticSourceConfig(DOCTORID, Set.of(), Map.of(DOCTORID, SOURCE)).source()).contains(SOURCE);
    }

    @Test
    void constructor_noSources_hasNoSource() {
        assertThat(new AuthenticSourceConfig(DOCTORID, Set.of(), Map.of()).source()).isEmpty();
    }

    @Test
    void constructor_sourceUnderAMisspelledType_isInvalid() {
        Map<String, SourceSettings> misspelled = Map.of("doctorid.sd1", SOURCE);

        assertThatThrownBy(() -> new AuthenticSourceConfig(DOCTORID, Set.of(), misspelled))
                .isInstanceOf(InvalidSourceConfigException.class)
                .hasMessage("authentic_source has an entry for another attestation type");
    }

    @Test
    void constructor_extraSourceForAnotherType_isInvalid() {
        Map<String, SourceSettings> two = Map.of(DOCTORID, SOURCE, "employee.sd.1", SOURCE);

        assertThatThrownBy(() -> new AuthenticSourceConfig(DOCTORID, Set.of(), two))
                .isInstanceOf(InvalidSourceConfigException.class);
    }
}
