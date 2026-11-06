package es.in2.issuanceportal.journey.infrastructure.systemtoken;

import es.in2.issuanceportal.journey.application.port.SystemTokenUnavailableException;
import es.in2.issuanceportal.journey.application.port.SystemTokenUnavailableException.Reason;
import lombok.extern.slf4j.Slf4j;
import net.eudistack.verifierclient.binding.CnfBindingValidator;
import net.eudistack.verifierclient.exception.CredentialKeyMismatchException;
import net.eudistack.verifierclient.jwt.ClientAssertionBuilder;
import net.eudistack.verifierclient.jwt.VpJwtBuilder;

/**
 * Builds the {@code client_assertion} of a {@code client_credentials} request to the Verifier (RFC 7523 with
 * a presentation) with the verifier client SDK: a self-signed presentation of the machine credential, wrapped
 * in an assertion signed by the same key. Before signing, the key is checked against the credential's
 * {@code cnf}: a pair that does not match is a misconfigured secret, and nothing is sent.
 */
@Slf4j
final class ClientAssertionFactory {

    /**
     * @param audience the Verifier's public URL, as the Verifier resolves it from the request
     * @throws SystemTokenUnavailableException with {@code NO_MACHINE_CREDENTIAL} when the key is not the
     *                                         credential's
     */
    String build(MachineIdentity identity, String audience) {
        String clientId = identity.credential().clientId();
        try {
            CnfBindingValidator.validate(identity.privateKey(), identity.credential());
        } catch (CredentialKeyMismatchException e) {
            // Only the fact is logged: the exception message is for the SDK's callers, not for the logs.
            log.warn("The private key of tenant {} machine credential does not match the credential's cnf key",
                    identity.credential().tenant());
            throw new SystemTokenUnavailableException(Reason.NO_MACHINE_CREDENTIAL);
        }
        String presentation = VpJwtBuilder.build(
                identity.privateKey(), clientId, identity.credential().compactJwt());
        return ClientAssertionBuilder.build(identity.privateKey(), clientId, audience, presentation);
    }
}
