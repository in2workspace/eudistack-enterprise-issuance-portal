package es.in2.issuanceportal.journey.domain.config;

import java.util.Map;

/**
 * The part of the portal configuration that only the service reads and {@code GET /config} never serves
 * (design.md AD-7). All optional here: the journey steps that use them check what they need.
 *
 * @param attestationType  {@code credential_configuration_id} issued to the tenant's holders
 * @param dataPlacement    attestation field → where its value comes from (identity or authentic source)
 * @param onboarding       onboarding tickets of the tenant
 * @param portalClientId   {@code client_id} of the portal at the tenant's Verifier
 * @param identifierTypes  attestation type accepted at login → personal identifier type
 */
public record ServiceSettings(String attestationType, Map<String, String> dataPlacement, Onboarding onboarding,
                              String portalClientId, Map<String, String> identifierTypes) {

    public static final ServiceSettings NONE = new ServiceSettings(null, null, null, null, null);

    public ServiceSettings {
        dataPlacement = ConfigValues.copy(dataPlacement);
        onboarding = onboarding == null ? Onboarding.DISABLED : onboarding;
        identifierTypes = ConfigValues.copy(identifierTypes);
    }
}
