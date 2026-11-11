package es.in2.issuanceportal.journey;

import es.in2.issuanceportal.journey.application.port.EmailCodeDeliveryException;
import es.in2.issuanceportal.journey.application.port.EmailCodeMailer;
import es.in2.issuanceportal.journey.application.port.EmailCodeMessage;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keeps the code emails for assertions, and can fail on demand like a mail provider that rejects or does not answer. */
public class RecordingEmailCodeMailer implements EmailCodeMailer {

    private static final Pattern CODE = Pattern.compile("(?<!\\d)\\d{6}(?!\\d)");

    private final List<EmailCodeMessage> sent = new CopyOnWriteArrayList<>();
    private volatile boolean failing;

    @Override
    public void send(EmailCodeMessage message) {
        if (failing) {
            throw new EmailCodeDeliveryException("SES MessageRejected");
        }
        sent.add(message);
    }

    public List<EmailCodeMessage> sent() {
        return List.copyOf(sent);
    }

    public EmailCodeMessage last() {
        return sent.getLast();
    }

    /** The 6 digits of a message. */
    public static String codeOf(EmailCodeMessage message) {
        Matcher matcher = CODE.matcher(message.body());
        if (!matcher.find()) {
            throw new AssertionError("The email carries no code");
        }
        return matcher.group();
    }

    public void failFromNowOn() {
        failing = true;
    }

    public void reset() {
        sent.clear();
        failing = false;
    }
}
