package es.in2.issuanceportal.journey.infrastructure.web;

import es.in2.issuanceportal.journey.domain.incident.Incident;
import es.in2.issuanceportal.journey.domain.incident.IncidentType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** {@code Incident} of the contract, with its required fields, lengths and formats. */
record IncidentRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Email String email,
        @Size(max = 30) String phone,
        @NotBlank @Pattern(regexp = "technical|access|credentials|other") String type,
        @NotBlank @Size(max = 200) String subject,
        @NotBlank @Size(max = 5000) String description) {

    Incident toIncident() {
        return new Incident(name, email, phone, IncidentType.fromCode(type), subject, description);
    }

    /** Never prints the content: it carries the holder's personal data. */
    @Override
    public String toString() {
        return "IncidentRequest[redacted]";
    }
}
