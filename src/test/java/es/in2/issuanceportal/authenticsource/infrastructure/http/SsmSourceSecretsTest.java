package es.in2.issuanceportal.authenticsource.infrastructure.http;

import es.in2.issuanceportal.MutableClock;
import es.in2.issuanceportal.authenticsource.application.port.SourceSecrets;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class SsmSourceSecretsTest {

    private final SsmClient ssm = mock(SsmClient.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-16T10:00:00Z"));
    private static final String TEMPLATE = "/eudistack/test/config/apps/issuance-portal/authentic-source/{tenant}/";
    private static final TenantId CGCOM = new TenantId("cgcom");
    private static final String REF = "/eudistack/test/config/apps/issuance-portal/authentic-source/cgcom/registro";
    private final SsmSourceSecrets secrets = new SsmSourceSecrets(() -> ssm, clock, Duration.ofMinutes(5), TEMPLATE);

    private void stored(String value) {
        when(ssm.getParameter(any(Consumer.class))).thenReturn(GetParameterResponse.builder()
                .parameter(Parameter.builder().value(value).build()).build());
    }

    @Test
    @DisplayName("AC-19 — secret_readTwiceWithinFiveMinutes_asksSsmOnce")
    void secret_readTwiceWithinFiveMinutes_asksSsmOnce() {
        stored("s3cret");

        assertThat(secrets.secret(CGCOM, REF)).isEqualTo("s3cret");
        clock.advance(Duration.ofMinutes(4));
        assertThat(secrets.secret(CGCOM, REF)).isEqualTo("s3cret");

        verify(ssm, times(1)).getParameter(any(Consumer.class));
    }

    @Test
    @DisplayName("AC-19 — secret_readAfterFiveMinutes_asksSsmAgain")
    void secret_readAfterFiveMinutes_asksSsmAgain() {
        stored("s3cret");
        secrets.secret(CGCOM, REF);

        clock.advance(Duration.ofMinutes(5));
        secrets.secret(CGCOM, REF);

        verify(ssm, times(2)).getParameter(any(Consumer.class));
    }

    @Test
    @DisplayName("AC-19 — secret_withDecryption_asksForTheParameterByName")
    void secret_withDecryption_asksForTheParameterByName() {
        GetParameterRequest[] sent = new GetParameterRequest[1];
        when(ssm.getParameter(any(Consumer.class))).thenAnswer(call -> {
            GetParameterRequest.Builder builder = GetParameterRequest.builder();
            ((Consumer<GetParameterRequest.Builder>) call.getArgument(0)).accept(builder);
            sent[0] = builder.build();
            return GetParameterResponse.builder().parameter(Parameter.builder().value("s3cret").build()).build();
        });

        secrets.secret(CGCOM, REF);

        assertThat(sent[0].name()).isEqualTo(REF);
        assertThat(sent[0].withDecryption()).isTrue();
    }

    @Test
    @DisplayName("AC-19 — secret_ssmFails_isUnavailableWithoutTheCause")
    void secret_ssmFails_isUnavailableWithoutTheCause() {
        when(ssm.getParameter(any(Consumer.class))).thenThrow(SdkClientException.create(REF));

        assertThatThrownBy(() -> secrets.secret(CGCOM, REF))
                .isInstanceOf(SourceSecrets.SourceSecretUnavailableException.class)
                .hasMessageNotContaining("eudistack");
    }

    @Test
    void secret_blankValue_isUnavailable() {
        stored(" ");

        assertThatThrownBy(() -> secrets.secret(CGCOM, REF))
                .isInstanceOf(SourceSecrets.SourceSecretUnavailableException.class);
    }

    @Test
    @DisplayName("EUD-359 AC-19 — secret_outsideTheTenantsPrefix_isUnavailableWithoutAskingSsm")
    void secret_outsideTheTenantsPrefix_isUnavailableWithoutAskingSsm() {
        stored("s3cret");

        for (String reference : List.of(
                "/eudistack/test/config/apps/issuance-portal/authentic-source/kpmg/registro",
                "/eudistack/test/config/apps/issuance-portal/machine-credential/cgcom",
                "/eudistack/test/config/apps/issuance-portal/authentic-source/cgcom/../../machine-credential/kpmg",
                "/eudistack/test/config/apps/issuance-portal/authentic-source/cgcom/",
                "/eudistack/test/config/apps/issuance-portal/authentic-source/cgcomx/registro",
                "eudistack/test/config/apps/issuance-portal/authentic-source/cgcom/registro")) {
            assertThatThrownBy(() -> secrets.secret(CGCOM, reference))
                    .isInstanceOf(SourceSecrets.SourceSecretUnavailableException.class)
                    .hasMessageNotContaining("eudistack");
        }

        verify(ssm, never()).getParameter(any(Consumer.class));
    }

    @Test
    @DisplayName("EUD-359 AC-19 — secret_withoutTemplate_noSecretIsRead")
    void secret_withoutTemplate_noSecretIsRead() {
        stored("s3cret");
        for (String template : List.of("", "/eudistack/test/config/apps/issuance-portal/authentic-source/")) {
            SsmSourceSecrets unconfigured = new SsmSourceSecrets(() -> ssm, clock, Duration.ofMinutes(5), template);

            assertThatThrownBy(() -> unconfigured.secret(CGCOM, REF))
                    .isInstanceOf(SourceSecrets.SourceSecretUnavailableException.class);
        }

        verify(ssm, never()).getParameter(any(Consumer.class));
    }
}
