package es.in2.issuanceportal.shared.tenant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicHostPropertiesTest {

    @Test
    void hostOf_tenant_replacesThePlaceholder() {
        assertThat(new PublicHostProperties("{tenant}.127.0.0.1.nip.io:4443").hostOf(new TenantId("sandbox")))
                .isEqualTo("sandbox.127.0.0.1.nip.io:4443");
    }

    @Test
    void new_templateWithoutTenant_isRejected() {
        assertThatThrownBy(() -> new PublicHostProperties("portal.example.test"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
