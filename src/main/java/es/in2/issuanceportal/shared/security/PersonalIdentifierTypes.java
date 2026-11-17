package es.in2.issuanceportal.shared.security;

import es.in2.issuanceportal.session.api.IdentifierType;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the holder's personal identifier is, from the login method the token says (design.md section 3.1), never from
 * the name of the claim: a certificate and a DoctorID attestation put their number in the same one.
 */
final class PersonalIdentifierTypes {

    /** {@code amr} values of a login with a certificate. */
    private static final List<String> CERTIFICATE_METHODS = List.of("hwk", "swk");

    private PersonalIdentifierTypes() {
    }

    /**
     * @param credentialType {@code credential_type}, present when the holder presented an attestation
     * @param methods        {@code amr}
     * @param byAttestation  the tenant's {@code identifier_types}: attestation type → identifier type
     * @return empty when the token is neither a certificate login nor an attestation the tenant maps
     */
    static Optional<IdentifierType> resolve(String credentialType, List<String> methods,
                                            Map<String, String> byAttestation) {
        if (credentialType != null && !credentialType.isBlank()) {
            return Optional.ofNullable(byAttestation.get(credentialType)).flatMap(IdentifierType::fromCode);
        }
        if (methods.stream().anyMatch(CERTIFICATE_METHODS::contains)) {
            return Optional.of(IdentifierType.PERSONAL_ID);
        }
        return Optional.empty();
    }
}
