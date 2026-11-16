package es.in2.issuanceportal.authenticsource.infrastructure.http;

import es.in2.issuanceportal.MutableClock;
import es.in2.issuanceportal.authenticsource.application.port.SourceSecrets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class SsmSourceSecretsTest {

    private final SsmClient ssm = mock(SsmClient.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-16T10:00:00Z"));
    private final SsmSourceSecrets secrets = new SsmSourceSecrets(() -> ssm, clock, Duration.ofMinutes(5));

    private void stored(String value) {
        when(ssm.getParameter(any(Consumer.class))).thenReturn(GetParameterResponse.builder()
                .parameter(Parameter.builder().value(value).build()).build());
    }

    @Test
    @DisplayName("AC-19 — secret_readTwiceWithinFiveMinutes_asksSsmOnce")
    void secret_readTwiceWithinFiveMinutes_asksSsmOnce() {
        stored("s3cret");

        assertThat(secrets.secret("eudistack/cgcom/registro")).isEqualTo("s3cret");
        clock.advance(Duration.ofMinutes(4));
        assertThat(secrets.secret("eudistack/cgcom/registro")).isEqualTo("s3cret");

        verify(ssm, times(1)).getParameter(any(Consumer.class));
    }

    @Test
    @DisplayName("AC-19 — secret_readAfterFiveMinutes_asksSsmAgain")
    void secret_readAfterFiveMinutes_asksSsmAgain() {
        stored("s3cret");
        secrets.secret("eudistack/cgcom/registro");

        clock.advance(Duration.ofMinutes(5));
        secrets.secret("eudistack/cgcom/registro");

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

        secrets.secret("eudistack/cgcom/registro");

        assertThat(sent[0].name()).isEqualTo("eudistack/cgcom/registro");
        assertThat(sent[0].withDecryption()).isTrue();
    }

    @Test
    @DisplayName("AC-19 — secret_ssmFails_isUnavailableWithoutTheCause")
    void secret_ssmFails_isUnavailableWithoutTheCause() {
        when(ssm.getParameter(any(Consumer.class))).thenThrow(SdkClientException.create("eudistack/cgcom/registro"));

        assertThatThrownBy(() -> secrets.secret("eudistack/cgcom/registro"))
                .isInstanceOf(SourceSecrets.SourceSecretUnavailableException.class)
                .hasMessageNotContaining("eudistack");
    }

    @Test
    void secret_blankValue_isUnavailable() {
        stored(" ");

        assertThatThrownBy(() -> secrets.secret("x"))
                .isInstanceOf(SourceSecrets.SourceSecretUnavailableException.class);
    }
}
