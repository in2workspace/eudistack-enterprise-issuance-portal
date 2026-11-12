package es.in2.issuanceportal.session.api;

/** The holder already has a reachable journey: a second session for the same holder cannot be opened. */
public class HolderAlreadyHasJourneyException extends RuntimeException {

    public HolderAlreadyHasJourneyException(Throwable cause) {
        super("The holder already has a journey session", cause);
    }
}
