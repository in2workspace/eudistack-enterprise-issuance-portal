package es.in2.issuanceportal.journey.application.port;

import es.in2.issuanceportal.journey.domain.incident.Incident;

import java.util.regex.Pattern;

/**
 * The email that carries an incident to the tenant's support (design.md AD-8): plain text, with the
 * holder's email as {@code Reply-To} so support answers the holder directly.
 */
public record IncidentMessage(String to, String replyTo, String subject, String body) {

    /** Line breaks and other control characters: in a subject they could add mail headers. */
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}+");

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
                """.formatted(incident.name(), incident.email(), incident.phoneIfGiven().orElse("no indicado"),
                incident.type().label(), incident.subject(), incident.description());
        return new IncidentMessage(supportEmail, incident.email(), singleLine(subject), body);
    }

    private static String singleLine(String text) {
        return CONTROL.matcher(text).replaceAll(" ").replaceAll(" {2,}", " ").trim();
    }

    /** Never prints the content: it carries the holder's personal data. */
    @Override
    public String toString() {
        return "IncidentMessage[redacted]";
    }
}
