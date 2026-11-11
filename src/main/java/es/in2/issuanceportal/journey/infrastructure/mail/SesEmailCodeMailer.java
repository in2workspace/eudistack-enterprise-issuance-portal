package es.in2.issuanceportal.journey.infrastructure.mail;

import es.in2.issuanceportal.journey.application.port.EmailCodeDeliveryException;
import es.in2.issuanceportal.journey.application.port.EmailCodeMailer;
import es.in2.issuanceportal.journey.application.port.EmailCodeMessage;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

import java.nio.charset.StandardCharsets;

/**
 * Sends the verification code with Amazon SES, from the platform's verified sending identity, the one the
 * incidents use (design.md §3.6). The client's call limit makes a provider that does not answer a failed send.
 */
class SesEmailCodeMailer implements EmailCodeMailer {

    private static final String CHARSET = StandardCharsets.UTF_8.name();

    private final SesV2Client ses;
    private final String from;

    SesEmailCodeMailer(SesV2Client ses, String from) {
        this.ses = ses;
        this.from = from;
    }

    @Override
    public void send(EmailCodeMessage message) {
        SendEmailRequest request = SendEmailRequest.builder()
                .fromEmailAddress(from)
                .destination(destination -> destination.toAddresses(message.to()))
                .content(content -> content.simple(simple -> simple
                        .subject(subject -> subject.data(message.subject()).charset(CHARSET))
                        .body(body -> body.text(text -> text.data(message.body()).charset(CHARSET)))))
                .build();
        try {
            ses.sendEmail(request);
        } catch (AwsServiceException e) {
            throw new EmailCodeDeliveryException("SES " + (e.awsErrorDetails() == null
                    ? e.statusCode() : e.awsErrorDetails().errorCode()));
        } catch (SdkException e) {
            throw new EmailCodeDeliveryException("SES unreachable (" + e.getClass().getSimpleName() + ")");
        }
    }
}
