package es.in2.issuanceportal.journey.application.port;

import es.in2.issuanceportal.journey.domain.EmailLanguage;
import es.in2.issuanceportal.journey.domain.EmailVerification;

/**
 * The email that carries the verification code (design.md §3.6): plain text, in the holder's language, with
 * the portal's name, the code, how long it lasts and a line for whoever did not ask for it. It has no links,
 * so it cannot be taken for an impersonation email.
 */
public record EmailCodeMessage(String to, String subject, String body) {

    public static EmailCodeMessage of(String to, String code, String portalName, EmailLanguage language) {
        long minutes = EmailVerification.CODE_LIFETIME.toMinutes();
        return switch (language) {
            case ES -> new EmailCodeMessage(to, "Tu código de verificación", """
                    Tu código de verificación en %s es:

                    %s

                    Caduca en %d minutos.

                    Si no lo has pedido tú, ignora este correo.
                    """.formatted(portalName, code, minutes));
            case EN -> new EmailCodeMessage(to, "Your verification code", """
                    Your verification code for %s is:

                    %s

                    It expires in %d minutes.

                    If you did not ask for it, ignore this email.
                    """.formatted(portalName, code, minutes));
        };
    }

    /** Never prints the content: it carries the holder's email and the code. */
    @Override
    public String toString() {
        return "EmailCodeMessage[redacted]";
    }
}
