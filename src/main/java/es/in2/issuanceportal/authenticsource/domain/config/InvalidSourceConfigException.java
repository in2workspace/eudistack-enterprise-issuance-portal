package es.in2.issuanceportal.authenticsource.domain.config;

/**
 * The tenant's authentic source configuration breaks the contract, so no search may reach the source.
 * The message names the field, never a value.
 */
public class InvalidSourceConfigException extends RuntimeException {

    public InvalidSourceConfigException(String message) {
        super(message);
    }
}
