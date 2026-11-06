package es.in2.issuanceportal.journey.domain;

/** The journey already has an issuance process and a different one was offered: it keeps a single one. */
public class IssuanceProcessAlreadyRecordedException extends RuntimeException {

    public IssuanceProcessAlreadyRecordedException() {
        super("issuance_process_already_recorded");
    }
}
