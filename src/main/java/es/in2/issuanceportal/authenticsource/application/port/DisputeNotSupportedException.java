package es.in2.issuanceportal.authenticsource.application.port;

/** The source cannot be told that the data of a request is not correct. It carries no data. */
public class DisputeNotSupportedException extends RuntimeException {

    public DisputeNotSupportedException() {
        super("Dispute not supported by the source", null, false, false);
    }
}
