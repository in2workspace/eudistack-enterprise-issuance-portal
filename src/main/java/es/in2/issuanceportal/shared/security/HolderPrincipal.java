package es.in2.issuanceportal.shared.security;

import es.in2.issuanceportal.session.api.IdentifierType;

import java.util.List;

/**
 * The holder who made the request, taken from a validated Access Token (design.md section 3.1). Controllers receive it
 * with {@code @AuthenticationPrincipal}.
 *
 * <p>The identity claims are as the token carries them: a token without them is still valid, and the step that needs
 * the identity decides what to do. The text form carries no personal data.
 *
 * @param issuer                       {@code iss}
 * @param subject                      {@code sub}
 * @param personalIdentifierType       what {@code personal_administrative_number} is, from the login method
 * @param credentialType               {@code credential_type}: the attestation the holder presented; {@code null}
 *                                     with a certificate
 * @param authenticationMethods        {@code amr}; empty when the token has none
 */
public record HolderPrincipal(String issuer, String subject, String givenName, String familyName,
                              String personalAdministrativeNumber, String credentialType,
                              List<String> authenticationMethods, IdentifierType personalIdentifierType) {

    public HolderPrincipal {
        authenticationMethods = authenticationMethods == null ? List.of() : List.copyOf(authenticationMethods);
    }

    @Override
    public String toString() {
        return "HolderPrincipal[personalIdentifierType=" + personalIdentifierType + ", personal data redacted]";
    }
}
