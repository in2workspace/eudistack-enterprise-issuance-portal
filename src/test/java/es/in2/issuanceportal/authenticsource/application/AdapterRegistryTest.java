package es.in2.issuanceportal.authenticsource.application;

import es.in2.issuanceportal.authenticsource.StubAdapter;
import es.in2.issuanceportal.authenticsource.application.port.AuthenticSourceAdapter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdapterRegistryTest {

    @Test
    void find_registeredType_returnsItsAdapter() {
        StubAdapter simulated = StubAdapter.searchingByBoth("simulated");
        AdapterRegistry registry = new AdapterRegistry(List.of(simulated, StubAdapter.searchingByBoth("cgcom")));

        assertThat(registry.find("simulated")).containsSame(simulated);
        assertThat(registry.types()).containsExactlyInAnyOrder("simulated", "cgcom");
    }

    @Test
    void find_unknownType_isEmpty() {
        assertThat(new AdapterRegistry(List.of()).find("simulated")).isEmpty();
    }

    @Test
    void constructor_sameNameTwice_refusesToStart() {
        List<AuthenticSourceAdapter> twice =
                List.of(StubAdapter.searchingByBoth("simulated"), StubAdapter.searchingByBoth("simulated"));

        assertThatThrownBy(() -> new AdapterRegistry(twice))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated");
    }
}
