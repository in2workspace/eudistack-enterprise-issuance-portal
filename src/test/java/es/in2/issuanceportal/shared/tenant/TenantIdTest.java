package es.in2.issuanceportal.shared.tenant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class TenantIdTest {

    @Test
    void parseNormalisesToLowercase() {
        assertThat(TenantId.parse(" Sandbox ")).contains(new TenantId("sandbox"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"sand box", "kpmg;drop", "dome\"", "tenant.name"})
    void parseRejectsAnythingThatIsNotAnIdentifier(String candidate) {
        assertThat(TenantId.parse(candidate)).isEmpty();
    }

    @Test
    void constructorRejectsAnInvalidIdentifier() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TenantId("Sandbox"));
    }

    @Test
    void schemaIsTheQuotedServiceSchemaOfTheTenant() {
        assertThat(new TenantId("sandbox").schema()).isEqualTo("\"sandbox_issuance_portal\"");
    }
}
