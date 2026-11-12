package es.in2.issuanceportal.journey.infrastructure.mail;

import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Locale;
import java.util.Properties;

/**
 * The TLS rules of the mail session, checked once at startup on the properties JavaMail will really use,
 * wherever they came from (the file, an environment variable, a system property): STARTTLS (or implicit TLS)
 * is required, the server's certificate must be valid for its host, and no trust override or custom socket
 * factory may exist. Anything else fails the start: the SES credentials never travel without a verified channel.
 */
final class SmtpTlsPolicy {

    private static final String PREFIX = "mail.smtp.";
    private static final String STARTTLS_REQUIRED = PREFIX + "starttls.required";
    private static final String SSL_ENABLE = PREFIX + "ssl.enable";
    private static final String CHECK_IDENTITY = PREFIX + "ssl.checkserveridentity";
    private static final String SSL_TRUST = PREFIX + "ssl.trust";
    private static final String SSL_SOCKET_FACTORY = PREFIX + "ssl.socketfactory";
    private static final String SOCKET_FACTORY = PREFIX + "socketfactory";

    private SmtpTlsPolicy() {
    }

    /** @return the same sender, when its session meets the rules */
    static JavaMailSender verified(JavaMailSender sender) {
        if (!(sender instanceof JavaMailSenderImpl impl)) {
            throw new IllegalStateException("The SMTP TLS settings cannot be checked on this mail sender");
        }
        if (!"smtp".equalsIgnoreCase(impl.getProtocol())) {
            throw new IllegalStateException("spring.mail.protocol must be smtp: the TLS settings are those of "
                    + PREFIX + "*");
        }
        Properties session = impl.getSession().getProperties();
        if (!isTrue(session, STARTTLS_REQUIRED) && !isTrue(session, SSL_ENABLE)) {
            throw new IllegalStateException(STARTTLS_REQUIRED + " (or " + SSL_ENABLE + ") must be true");
        }
        if (!isTrue(session, CHECK_IDENTITY)) {
            throw new IllegalStateException(CHECK_IDENTITY + " must be true");
        }
        for (String name : session.stringPropertyNames()) {
            // Whatever the case: a lower-cased name from an environment variable is as forbidden as the real one.
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.equals(SSL_TRUST) || lower.startsWith(SSL_SOCKET_FACTORY) || lower.startsWith(SOCKET_FACTORY)) {
                throw new IllegalStateException(name + " must not be set");
            }
        }
        return sender;
    }

    private static boolean isTrue(Properties session, String name) {
        return "true".equalsIgnoreCase(session.getProperty(name));
    }
}
