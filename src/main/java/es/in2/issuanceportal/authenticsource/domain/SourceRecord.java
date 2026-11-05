package es.in2.issuanceportal.authenticsource.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the authentic source holds about a person: all their identifiers, whether they are entitled and
 * their attributes by the source's own names. Its text form carries no personal data.
 */
public record SourceRecord(List<PersonalIdentifier> identifiers, boolean entitled, Map<String, Object> attributes) {

    public SourceRecord {
        identifiers = identifiers == null ? List.of() : List.copyOf(identifiers);
        attributes = attributes == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    /** The person's identifier of the given type, if the source returned one. */
    public Optional<PersonalIdentifier> identifierOf(IdentifierType type) {
        return identifiers.stream().filter(identifier -> identifier.type() == type).findFirst();
    }

    @Override
    public String toString() {
        return "SourceRecord[entitled=" + entitled + ", attributes=" + attributes.keySet()
                + ", personal data redacted]";
    }
}
