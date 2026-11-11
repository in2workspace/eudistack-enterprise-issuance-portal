package es.in2.issuanceportal.journey.application.port;

import es.in2.issuanceportal.journey.domain.EmailLanguage;
import es.in2.issuanceportal.journey.domain.EmailVerification;

import java.util.regex.Pattern;

/**
 * The email that carries the verification code (design.md §3.6), in the holder's language, with the portal's
 * name, the code, how long it lasts and a line for whoever did not ask for it. It has no links, so it cannot be
 * taken for an impersonation email. {@code body} is its plain-text version; the mail adapter also renders the
 * HTML one from {@code tenant} (its branding), {@code code}, {@code portalName} and {@code language}.
 */
public record EmailCodeMessage(String to, String subject, String body, String tenant, String code,
                               String portalName, EmailLanguage language) {

    /**
     * Line breaks, other control characters, the Unicode line and paragraph separators, and invisible format
     * characters: the portal's name is a tenant's setting and goes into the subject, where they could add mail
     * headers.
     */
    private static final Pattern BREAKS = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]+");

    public static EmailCodeMessage of(String to, String code, String portalName, EmailLanguage language,
                                      String tenant) {
        String name = BREAKS.matcher(portalName).replaceAll(" ").replaceAll(" {2,}", " ").trim();
        long minutes = EmailVerification.CODE_LIFETIME.toMinutes();
        return switch (language) {
            case ES -> new EmailCodeMessage(to, "Tu código de verificación — " + name, """
                    Tu código de verificación en %s es:

                    %s

                    Caduca en %d minutos.

                    Si no lo has pedido tú, ignora este correo.
                    """.formatted(name, code, minutes), tenant, code, name, language);
            case EN -> new EmailCodeMessage(to, "Your verification code — " + name, """
                    Your verification code for %s is:

                    %s

                    It expires in %d minutes.

                    If you did not ask for it, ignore this email.
                    """.formatted(name, code, minutes), tenant, code, name, language);
        };
    }

    /** Never prints the content: it carries the holder's email and the code. */
    @Override
    public String toString() {
        return "EmailCodeMessage[redacted]";
    }
}
