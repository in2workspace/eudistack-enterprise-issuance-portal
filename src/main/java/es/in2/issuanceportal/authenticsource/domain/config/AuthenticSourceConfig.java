package es.in2.issuanceportal.authenticsource.domain.config;

import es.in2.issuanceportal.authenticsource.domain.IdentifierType;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What the module reads of a tenant's {@code portal_config}: the attestation type its holders get, the
 * identifier types its logins can give, and at most one source per attestation type (design.md AD-2).
 *
 * @param attestationType      {@code attestation_type}: the type the journey issues
 * @param loginIdentifierTypes {@code dni}, which the certificate login gives, plus the types
 *                             {@code identifier_types} assigns to the attestations accepted at login
 * @param sources              {@code authentic_source}: attestation type → its source
 */
public record AuthenticSourceConfig(String attestationType, Set<IdentifierType> loginIdentifierTypes,
                                    Map<String, SourceSettings> sources) {

    public AuthenticSourceConfig {
        if (attestationType == null || attestationType.isBlank()) {
            throw new InvalidSourceConfigException("attestation_type is required");
        }
        EnumSet<IdentifierType> types = EnumSet.of(IdentifierType.DNI);
        if (loginIdentifierTypes != null) {
            types.addAll(loginIdentifierTypes);
        }
        loginIdentifierTypes = Collections.unmodifiableSet(types);
        sources = sources == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(sources));
        // A tenant issues one attestation type, so any other key is a mistake. Accepting it would leave the
        // type without a source, and a type without a source confirms every holder (FR-06).
        if (!sources.keySet().stream().allMatch(attestationType::equals)) {
            throw new InvalidSourceConfigException("authentic_source has an entry for another attestation type");
        }
    }

    /** The source of the journey's attestation type; none means the identity alone is enough (FR-06). */
    public Optional<SourceSettings> source() {
        return Optional.ofNullable(sources.get(attestationType));
    }
}
