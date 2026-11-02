package es.in2.issuanceportal.journey.domain.incident;

import java.util.Objects;
import java.util.Optional;

/**
 * An incident a holder sends to the tenant's support (prd.md §3.5). It carries personal data: it is
 * never stored, logged or traced, only delivered.
 *
 * @param phone optional
 */
public record Incident(String name, String email, String phone, IncidentType type, String subject,
                       String description) {

    public Incident {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(description, "description");
    }

    public Optional<String> phoneIfGiven() {
        return phone == null || phone.isBlank() ? Optional.empty() : Optional.of(phone);
    }

    /** Never prints the content, so an incident that ends up in a log or a stack trace leaks nothing. */
    @Override
    public String toString() {
        return "Incident[type=" + type.code() + "]";
    }
}
