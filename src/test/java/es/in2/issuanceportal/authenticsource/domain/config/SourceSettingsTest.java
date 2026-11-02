package es.in2.issuanceportal.authenticsource.domain.config;

import es.in2.issuanceportal.authenticsource.domain.IdentifierType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceSettingsTest {

    private static final Set<IdentifierType> BOTH = Set.of(IdentifierType.DNI, IdentifierType.COLEGIADO);
    private static final List<AttributeMapping> MAPPING =
            List.of(new AttributeMapping("registration_number", "personal_administrative_number", "Número de colegiado"));

    private static SourceSettings settings(Duration timeout) {
        return new SourceSettings("DoctorID", "simulated", null, null, timeout, BOTH, MAPPING, null, Map.of());
    }

    @Test
    void constructor_withoutTimeout_waitsTenSeconds() {
        assertThat(settings(null).timeout()).isEqualTo(Duration.ofSeconds(10));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 11})
    void constructor_timeoutOutOfRange_isInvalid(long seconds) {
        Duration timeout = Duration.ofSeconds(seconds);

        assertThatThrownBy(() -> settings(timeout))
                .isInstanceOf(InvalidSourceConfigException.class)
                .hasMessage("timeout_seconds must be between 1 and 10");
    }

    @Test
    void constructor_withoutIdentifierTypes_isInvalid() {
        assertThatThrownBy(() -> new SourceSettings("DoctorID", "simulated", null, null, null, Set.of(), MAPPING,
                null, null))
                .isInstanceOf(InvalidSourceConfigException.class)
                .hasMessage("identifier_types is required");
    }

    @Test
    void constructor_withoutAdapter_isInvalid() {
        assertThatThrownBy(() -> new SourceSettings("DoctorID", " ", null, null, null, BOTH, MAPPING, null, null))
                .isInstanceOf(InvalidSourceConfigException.class)
                .hasMessage("adapter is required");
    }

    @Test
    void constructor_sameClaimTwice_isInvalid() {
        List<AttributeMapping> twice = List.of(new AttributeMapping("a", "medical_board", "Colegio"),
                new AttributeMapping("b", "medical_board", null));

        assertThatThrownBy(() -> new SourceSettings("DoctorID", "simulated", null, null, null, BOTH, twice, null,
                null))
                .isInstanceOf(InvalidSourceConfigException.class)
                .hasMessage("mapping has a claim twice");
    }

    @Test
    void attributeMapping_withoutLabel_isNotShown() {
        assertThat(new AttributeMapping("a", "legally_entitled", " ").shown()).isFalse();
        assertThat(MAPPING.getFirst().shown()).isTrue();
    }

    @Test
    void authenticSourceConfig_alwaysCoversTheCertificateLogin() {
        AuthenticSourceConfig config = new AuthenticSourceConfig("doctorid.sd.1", Set.of(IdentifierType.COLEGIADO),
                Map.of());

        assertThat(config.loginIdentifierTypes()).containsExactlyInAnyOrder(IdentifierType.DNI, IdentifierType.COLEGIADO);
        assertThat(config.source()).isEmpty();
    }
}
