package es.in2.issuanceportal.journey.infrastructure.walletunit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletUnitServicePropertiesTest {

    @Test
    void bind_nothingSet_waitsFiveSeconds() {
        WalletUnitServiceProperties properties = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("issuance-portal.wallet-unit-service", Bindable.of(WalletUnitServiceProperties.class));

        assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void new_zeroOrNegativeTimeout_isRejected() {
        Duration negative = Duration.ofSeconds(-1);

        assertThatThrownBy(() -> new WalletUnitServiceProperties(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WalletUnitServiceProperties(negative))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
