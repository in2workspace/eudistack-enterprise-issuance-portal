package es.in2.issuanceportal.session.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;
import java.util.Optional;

/**
 * Contract of the {@link Section#IDENTITY} section: the holder's verified identity, taken from the
 * Access Token. The Journey module writes it when it opens the journey; the Authentic source module
 * reads it. Its text form carries no personal data.
 *
 * @param email the email of the Access Token, if it carries one; {@code null} otherwise
 */
public record HolderIdentity(String givenName, String familyName, PersonalIdentifier personalIdentifier,
                             String email) {

    public HolderIdentity {
        requireText(givenName, "given_name");
        requireText(familyName, "family_name");
        Objects.requireNonNull(personalIdentifier, "personal_identifier");
        email = email == null || email.isBlank() ? null : email;
    }

    public Optional<String> emailIfPresent() {
        return Optional.ofNullable(email);
    }

    public String toJson() {
        return SectionJson.write(new Document(givenName, familyName,
                new IdentifierDocument(personalIdentifier.type().code(), personalIdentifier.value()), email));
    }

    /**
     * Reads the section.
     *
     * @throws InvalidSectionException naming the field when a required one is missing or empty, or the
     *                                 identifier type is not {@code dni} or {@code colegiado}
     */
    public static HolderIdentity fromJson(String json) {
        Document document = SectionJson.read(json, Document.class);
        requireText(document.givenName(), "given_name");
        requireText(document.familyName(), "family_name");
        IdentifierDocument identifier = document.personalIdentifier();
        if (identifier == null) {
            throw new InvalidSectionException("personal_identifier");
        }
        IdentifierType type = IdentifierType.fromCode(identifier.type())
                .orElseThrow(() -> new InvalidSectionException("personal_identifier.type"));
        requireText(identifier.value(), "personal_identifier.value");
        return new HolderIdentity(document.givenName(), document.familyName(),
                new PersonalIdentifier(type, identifier.value()), document.email());
    }

    @Override
    public String toString() {
        return "HolderIdentity[" + personalIdentifier + ", personal data redacted]";
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidSectionException(field);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Document(@JsonProperty("given_name") String givenName,
                    @JsonProperty("family_name") String familyName,
                    @JsonProperty("personal_identifier") IdentifierDocument personalIdentifier,
                    @JsonProperty("email") String email) {
    }

    record IdentifierDocument(@JsonProperty("type") String type, @JsonProperty("value") String value) {
    }
}
