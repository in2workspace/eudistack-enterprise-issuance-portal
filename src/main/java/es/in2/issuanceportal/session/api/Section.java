package es.in2.issuanceportal.session.api;

/**
 * The parts of the journey session. Each one is written by a single module and read by the others
 * (design.md AD-3).
 */
public enum Section {

    /** Verified identity and method. Written by Identification. */
    IDENTITY,
    /** Entitlement check result and attestation data. Written by Authentic source. */
    ENTITLEMENT,
    /** Issuance requests with their idempotency keys, offer and its expiry. Written by Journey. */
    ISSUANCE,
    /** The holder's email. Written by Journey. */
    EMAIL
}
