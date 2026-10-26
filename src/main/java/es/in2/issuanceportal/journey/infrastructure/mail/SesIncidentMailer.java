package es.in2.issuanceportal.journey.infrastructure.mail;

import es.in2.issuanceportal.journey.application.port.IncidentDeliveryException;
import es.in2.issuanceportal.journey.application.port.IncidentMailer;
import es.in2.issuanceportal.journey.application.port.IncidentMessage;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

import java.nio.charset.StandardCharsets;

/** Sends the incident with Amazon SES, from the platform's verified sending identity (design.md AD-8). */
class SesIncidentMailer implements IncidentMailer {

    private static final String CHARSET = StandardCharsets.UTF_8.name();

    private final SesV2Client ses;
    private final String from;

    SesIncidentMailer(SesV2Client ses, String from) {
        this.ses = ses;
        this.from = from;
    }

    @Override
    public void send(IncidentMessage message) {
        SendEmailRequest request = SendEmailRequest.builder()
                .fromEmailAddress(from)
                .destination(destination -> destination.toAddresses(message.to()))
                .replyToAddresses(message.replyTo())
                .content(content -> content.simple(simple -> simple
                        .subject(subject -> subject.data(message.subject()).charset(CHARSET))
                        .body(body -> body.text(text -> text.data(message.body()).charset(CHARSET)))))
                .build();
        try {
            ses.sendEmail(request);
        } catch (AwsServiceException e) {
            throw new IncidentDeliveryException("SES " + (e.awsErrorDetails() == null
                    ? e.statusCode() : e.awsErrorDetails().errorCode()));
        } catch (SdkException e) {
            throw new IncidentDeliveryException("SES unreachable (" + e.getClass().getSimpleName() + ")");
        }
    }
}
