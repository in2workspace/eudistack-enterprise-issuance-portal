package es.in2.issuanceportal.authenticsource.domain;

import es.in2.issuanceportal.authenticsource.domain.config.AttributeMapping;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * What the Data step shows (openapi {@code AttestationPreview}): the holder's name from the identity and
 * the labelled attestation data. The identity's personal identifier is never added; a claim reaches the
 * preview only if the tenant's mapping gives it a label. Its text form carries no personal data.
 */
public record AttestationPreview(String typeName, List<Field> fields, String email, boolean emailRequired) {

    /** Label of the holder's name, the first field. */
    static final String NAME_LABEL = "Nombre y apellidos";

    public AttestationPreview {
        fields = List.copyOf(fields);
        email = email == null || email.isBlank() ? null : email;
    }

    /**
     * @param identityEmail the email of the Access Token, preferred over the source's
     * @param sourceEmail   the email the source returned, if any
     */
    public static AttestationPreview of(String typeName, String givenName, String familyName,
                                        List<AttributeMapping> mapping, Map<String, Object> claims,
                                        String identityEmail, Object sourceEmail) {
        List<Field> fields = new ArrayList<>();
        fields.add(new Field(NAME_LABEL, givenName + " " + familyName));
        for (AttributeMapping entry : mapping) {
            Object value = claims.get(entry.claim());
            if (entry.shown() && value != null) {
                fields.add(new Field(entry.label(), displayed(value)));
            }
        }
        String email = firstText(identityEmail, sourceEmail);
        return new AttestationPreview(typeName, fields, email, email == null);
    }

    private static String firstText(String identityEmail, Object sourceEmail) {
        if (identityEmail != null && !identityEmail.isBlank()) {
            return identityEmail;
        }
        return sourceEmail instanceof String text && !text.isBlank() ? text : null;
    }

    private static String displayed(Object value) {
        return switch (value) {
            case Boolean flag -> flag ? "Sí" : "No";
            case Collection<?> values -> values.stream().map(String::valueOf).collect(Collectors.joining(", "));
            default -> String.valueOf(value);
        };
    }

    @Override
    public String toString() {
        return "AttestationPreview[typeName=" + typeName + ", fields=" + fields.size() + ", emailRequired="
                + emailRequired + ", personal data redacted]";
    }

    /** A labelled value. */
    public record Field(String label, String value) {

        @Override
        public String toString() {
            return "Field[label=" + label + ", value=redacted]";
        }
    }
}
