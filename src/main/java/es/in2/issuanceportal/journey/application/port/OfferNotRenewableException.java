package es.in2.issuanceportal.journey.application.port;

/**
 * The Issuer cannot give another offer for the process: it is no longer a draft (the holder collected the
 * attestation, or it was withdrawn or revoked) or it has no credential offer. It carries nothing: the response may
 * quote the process.
 */
public class OfferNotRenewableException extends RuntimeException {

    public OfferNotRenewableException() {
        super("Offer not renewable", null, false, false);
    }
}
