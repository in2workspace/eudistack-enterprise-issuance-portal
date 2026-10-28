package es.in2.issuanceportal.authenticsource.application;

import es.in2.issuanceportal.authenticsource.StubAdapter;
import es.in2.issuanceportal.authenticsource.domain.IdentifierType;
import es.in2.issuanceportal.authenticsource.domain.config.AuthenticSourceConfig;
import es.in2.issuanceportal.authenticsource.domain.config.SourceSettings;
import es.in2.issuanceportal.shared.error.ProblemCode;
import es.in2.issuanceportal.shared.error.ProblemException;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class AuthenticSourceConfigsTest {

    private static final TenantId CGCOM = new TenantId("cgcom");
    private static final Set<IdentifierType> BOTH = Set.of(IdentifierType.DNI, IdentifierType.COLEGIADO);

    private final Map<TenantId, AuthenticSourceConfig> rows = new HashMap<>();
    private final StubAdapter simulated = StubAdapter.searchingByBoth("simulated");
    private final AuthenticSourceConfigs configs = new AuthenticSourceConfigs(
            tenant -> Optional.ofNullable(rows.get(tenant)), new AdapterRegistry(List.of(simulated)));

    private static SourceSettings source(String adapter, Set<IdentifierType> types) {
        return new SourceSettings("DoctorID", adapter, null, null, null, types, List.of(), null, null);
    }

    private void save(Set<IdentifierType> loginTypes, SourceSettings source) {
        rows.put(CGCOM, new AuthenticSourceConfig("doctorid.sd.1", loginTypes,
                source == null ? Map.of() : Map.of("doctorid.sd.1", source)));
    }

    @Test
    void current_validSource_returnsItsSettingsAndAdapter() {
        save(Set.of(IdentifierType.COLEGIADO), source("simulated", BOTH));

        AuthenticSourceConfigs.Current current = configs.current(CGCOM);

        assertThat(current.hasSource()).isTrue();
        assertThat(current.adapter()).isSameAs(simulated);
        assertThat(current.attestationType()).isEqualTo("doctorid.sd.1");
    }

    @Test
    @DisplayName("AC-10 — current_attestationTypeWithoutSource_hasNoSource")
    void current_attestationTypeWithoutSource_hasNoSource() {
        save(Set.of(), null);

        assertThat(configs.current(CGCOM).hasSource()).isFalse();
    }

    @Test
    @DisplayName("ES-03 — current_noConfiguration_isConfigUnavailable")
    void current_noConfiguration_isConfigUnavailable() {
        assertThatThrownBy(() -> configs.current(CGCOM)).isInstanceOfSatisfying(ProblemException.class,
                e -> assertThat(e.problem()).isEqualTo(ProblemCode.CONFIG_UNAVAILABLE));
    }

    @Test
    @DisplayName("ES-03 — current_adapterNotInThisEnvironment_isConfigUnavailable")
    void current_adapterNotInThisEnvironment_isConfigUnavailable() {
        save(Set.of(), source("cgcom", BOTH));

        assertThatThrownBy(() -> configs.current(CGCOM)).isInstanceOfSatisfying(ProblemException.class,
                e -> assertThat(e.problem()).isEqualTo(ProblemCode.CONFIG_UNAVAILABLE));
    }

    @Test
    @DisplayName("AC-13 — current_typeTheAdapterCannotSearchBy_isConfigUnavailable")
    void current_typeTheAdapterCannotSearchBy_isConfigUnavailable() {
        AuthenticSourceConfigs dniOnly = new AuthenticSourceConfigs(tenant -> Optional.ofNullable(rows.get(tenant)),
                new AdapterRegistry(List.of(new StubAdapter("simulated", Set.of(IdentifierType.DNI)))));
        save(Set.of(), source("simulated", BOTH));

        assertThatThrownBy(() -> dniOnly.current(CGCOM)).isInstanceOfSatisfying(ProblemException.class,
                e -> assertThat(e.problem()).isEqualTo(ProblemCode.CONFIG_UNAVAILABLE));
    }

    @Test
    @DisplayName("AC-13 — current_sourceNotCoveringTheDoctorIdLogin_isConfigUnavailable")
    void current_sourceNotCoveringTheDoctorIdLogin_isConfigUnavailable(CapturedOutput output) {
        save(Set.of(IdentifierType.COLEGIADO), source("simulated", Set.of(IdentifierType.DNI)));

        assertThatThrownBy(() -> configs.current(CGCOM)).isInstanceOfSatisfying(ProblemException.class,
                e -> assertThat(e.problem()).isEqualTo(ProblemCode.CONFIG_UNAVAILABLE));
        assertThat(output.getOut()).contains("Authentic source configuration of tenant cgcom for doctorid.sd.1 is invalid: "
                + "authentic_source.identifier_types does not cover every type the login can give");
    }

    @Test
    @DisplayName("AC-13 — current_sourceNotCoveringTheCertificateLogin_isConfigUnavailable")
    void current_sourceNotCoveringTheCertificateLogin_isConfigUnavailable() {
        save(Set.of(), source("simulated", Set.of(IdentifierType.COLEGIADO)));

        assertThatThrownBy(() -> configs.current(CGCOM)).isInstanceOfSatisfying(ProblemException.class,
                e -> assertThat(e.problem()).isEqualTo(ProblemCode.CONFIG_UNAVAILABLE));
        assertThat(simulated.searches()).isEmpty();
    }
}
