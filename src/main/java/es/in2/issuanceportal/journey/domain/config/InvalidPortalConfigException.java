package es.in2.issuanceportal.journey.domain.config;

/**
 * The tenant's portal configuration breaks the contract. It is handled as if the tenant had no
 * configuration (design.md AD-7). The message names the field, never a value.
 */
public class InvalidPortalConfigException extends RuntimeException {

    public InvalidPortalConfigException(String message) {
        super(message);
    }
}
