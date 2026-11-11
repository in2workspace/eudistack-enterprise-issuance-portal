package es.in2.issuanceportal.journey.infrastructure.mail;

import es.in2.issuanceportal.journey.application.port.EmailCodeDeliveryException;
import es.in2.issuanceportal.journey.application.port.EmailCodeMessage;
import es.in2.issuanceportal.journey.domain.EmailLanguage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.MessageRejectedException;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SesEmailCodeMailerTest {

    private static final String FROM = "noreply@mail.stg.eudistack.net";
    private static final EmailCodeMessage MESSAGE = EmailCodeMessage.of("ana@example.org", "042042",
            "DoctorID", EmailLanguage.ES);

    private final SesV2Client ses = mock(SesV2Client.class);
    private final SesEmailCodeMailer mailer = new SesEmailCodeMailer(ses, FROM);

    @Test
    @DisplayName("AC-01 — send_message_sendsPlainTextFromThePlatformToTheHolderOnly")
    void send_message_sendsPlainTextFromThePlatformToTheHolderOnly() {
        when(ses.sendEmail(any(SendEmailRequest.class))).thenReturn(SendEmailResponse.builder().messageId("m-1").build());

        mailer.send(MESSAGE);

        ArgumentCaptor<SendEmailRequest> sent = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(ses).sendEmail(sent.capture());
        SendEmailRequest request = sent.getValue();
        assertThat(request.fromEmailAddress()).isEqualTo(FROM);
        assertThat(request.destination().toAddresses()).containsExactly("ana@example.org");
        assertThat(request.destination().ccAddresses()).isEmpty();
        assertThat(request.destination().bccAddresses()).isEmpty();
        assertThat(request.replyToAddresses()).isEmpty();
        assertThat(request.content().simple().subject().data()).isEqualTo("Tu código de verificación");
        assertThat(request.content().simple().body().text().data()).contains("042042", "DoctorID");
        assertThat(request.content().simple().body().html()).isNull();
    }

    @Test
    @DisplayName("AC-06 — send_sesRejects_failsWithTheCodeOnly")
    void send_sesRejects_failsWithTheCodeOnly() {
        when(ses.sendEmail(any(SendEmailRequest.class))).thenThrow(MessageRejectedException.builder()
                .message("Email address is not verified: ana@example.org")
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("MessageRejected").build())
                .build());

        assertThatThrownBy(() -> mailer.send(MESSAGE))
                .isInstanceOf(EmailCodeDeliveryException.class)
                .hasMessageContaining("MessageRejected")
                .hasMessageNotContaining("ana@example.org")
                .hasNoCause();
    }

    @Test
    @DisplayName("AC-06 — send_sesUnreachable_failsWithoutCause")
    void send_sesUnreachable_failsWithoutCause() {
        when(ses.sendEmail(any(SendEmailRequest.class)))
                .thenThrow(SdkClientException.create("Unable to execute HTTP request to ana@example.org"));

        assertThatThrownBy(() -> mailer.send(MESSAGE))
                .isInstanceOf(EmailCodeDeliveryException.class)
                .hasMessageContaining("SES unreachable")
                .hasMessageNotContaining("ana@example.org")
                .hasNoCause();
    }

    /**
     * NFR-1: a SES that accepts the connection and never answers is a failed send after the call limit of the
     * client the service builds, not a request that waits.
     */
    @Test
    @DisplayName("NFR-1 — send_sesThatNeverAnswers_failsAtTheFiveSecondLimit")
    void send_sesThatNeverAnswers_failsAtTheFiveSecondLimit() throws IOException {
        try (SilentServer silent = new SilentServer();
             SesV2Client client = IncidentMailConfiguration.sesClient(Region.EU_WEST_1,
                     StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")), silent.uri())) {
            SesEmailCodeMailer silentMailer = new SesEmailCodeMailer(client, FROM);
            long started = System.nanoTime();

            assertThatThrownBy(() -> silentMailer.send(MESSAGE))
                    .isInstanceOf(EmailCodeDeliveryException.class)
                    .hasMessageContaining("SES unreachable")
                    .hasNoCause();

            Duration waited = Duration.ofNanos(System.nanoTime() - started);
            assertThat(IncidentMailConfiguration.SES_CALL_TIMEOUT).isEqualTo(Duration.ofSeconds(5));
            assertThat(waited).isBetween(Duration.ofMillis(4_500), Duration.ofSeconds(8));
        }
    }

    /** Accepts connections and reads nothing back: the peer of a provider that hangs. */
    private static final class SilentServer implements AutoCloseable {

        private final ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        private final List<Socket> accepted = new ArrayList<>();
        private final Thread acceptor;

        SilentServer() throws IOException {
            acceptor = Thread.ofVirtual().start(() -> {
                try {
                    while (!server.isClosed()) {
                        synchronized (accepted) {
                            accepted.add(server.accept());
                        }
                    }
                } catch (IOException _) {
                    // Closed by the test.
                }
            });
        }

        URI uri() {
            return URI.create("http://127.0.0.1:" + server.getLocalPort());
        }

        @Override
        public void close() throws IOException {
            server.close();
            synchronized (accepted) {
                for (Socket socket : accepted) {
                    socket.close();
                }
            }
            acceptor.interrupt();
        }
    }
}
