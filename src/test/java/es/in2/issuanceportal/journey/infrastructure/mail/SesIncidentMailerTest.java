package es.in2.issuanceportal.journey.infrastructure.mail;

import es.in2.issuanceportal.journey.application.port.IncidentDeliveryException;
import es.in2.issuanceportal.journey.application.port.IncidentMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.MessageRejectedException;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SesIncidentMailerTest {

    private static final String FROM = "noreply@mail.stg.eudistack.net";
    private static final IncidentMessage MESSAGE = new IncidentMessage(
            "soporte@cgcom-identidad.es", "ana@example.org", "[Incidencia] Otro: Duda", "Nombre: Ana\n");

    private final SesV2Client ses = mock(SesV2Client.class);
    private final SesIncidentMailer mailer = new SesIncidentMailer(ses, FROM);

    @Test
    @DisplayName("AC-04 — send_message_sendsPlainTextFromPlatformToSupportWithReplyTo")
    void send_message_sendsPlainTextFromPlatformToSupportWithReplyTo() {
        when(ses.sendEmail(any(SendEmailRequest.class))).thenReturn(SendEmailResponse.builder().messageId("m-1").build());

        mailer.send(MESSAGE);

        ArgumentCaptor<SendEmailRequest> sent = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(ses).sendEmail(sent.capture());
        SendEmailRequest request = sent.getValue();
        assertThat(request.fromEmailAddress()).isEqualTo(FROM);
        assertThat(request.destination().toAddresses()).containsExactly("soporte@cgcom-identidad.es");
        assertThat(request.destination().ccAddresses()).isEmpty();
        assertThat(request.destination().bccAddresses()).isEmpty();
        assertThat(request.replyToAddresses()).containsExactly("ana@example.org");
        assertThat(request.content().simple().subject().data()).isEqualTo("[Incidencia] Otro: Duda");
        assertThat(request.content().simple().body().text().data()).isEqualTo("Nombre: Ana\n");
        assertThat(request.content().simple().body().html()).isNull();
    }

    @Test
    @DisplayName("ES-03 — send_sesRejects_failsWithCodeOnly")
    void send_sesRejects_failsWithCodeOnly() {
        when(ses.sendEmail(any(SendEmailRequest.class))).thenThrow(MessageRejectedException.builder()
                .message("Email address is not verified: soporte@cgcom-identidad.es")
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("MessageRejected").build())
                .build());

        assertThatThrownBy(() -> mailer.send(MESSAGE))
                .isInstanceOf(IncidentDeliveryException.class)
                .hasMessageContaining("MessageRejected")
                .hasMessageNotContaining("soporte@cgcom-identidad.es")
                .hasNoCause();
    }

    @Test
    @DisplayName("ES-03 — send_sesUnreachable_failsWithoutCause")
    void send_sesUnreachable_failsWithoutCause() {
        when(ses.sendEmail(any(SendEmailRequest.class)))
                .thenThrow(SdkClientException.create("Unable to execute HTTP request"));

        assertThatThrownBy(() -> mailer.send(MESSAGE))
                .isInstanceOf(IncidentDeliveryException.class)
                .hasMessageContaining("SES unreachable")
                .hasNoCause();
    }
}
