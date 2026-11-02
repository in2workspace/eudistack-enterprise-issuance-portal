package es.in2.issuanceportal.journey.application.port;

import es.in2.issuanceportal.journey.domain.incident.Incident;

import java.util.regex.Pattern;

/**
 * The email that carries an incident to the tenant's support (design.md AD-8): plain text, with the
 * holder's email as {@code Reply-To} so support answers the holder directly.
 */
public record IncidentMessage(String to, String replyTo, String subject, String body) {

    /**
     * Line breaks, other control characters, the Unicode line and paragraph separators, and invisible format
     * characters (bidi overrides, zero-width): in a subject they could add mail headers or disguise it, in a
     * body field they could fake another field.
     */
    private static final Pattern BREAKS = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]+");

    public static IncidentMessage of(Incident incident, String supportEmail) {
        String subject = "[Incidencia] " + incident.type().label() + ": " + incident.subject();
        String body = """
                Nombre: %s
                Correo: %s
                Teléfono: %s
                Tipo: %s
                Asunto: %s

                Descripción:
                %s
                """.formatted(singleLine(incident.name()), singleLine(incident.email()),
                incident.phoneIfGiven().map(IncidentMessage::singleLine).orElse("no indicado"),
                incident.type().label(), singleLine(incident.subject()), incident.description());
        return new IncidentMessage(supportEmail, incident.email(), singleLine(subject), body);
    }

    private static String singleLine(String text) {
        return BREAKS.matcher(text).replaceAll(" ").replaceAll(" {2,}", " ").trim();
    }

    /** Never prints the content: it carries the holder's personal data. */
    @Override
    public String toString() {
        return "IncidentMessage[redacted]";
    }
}
