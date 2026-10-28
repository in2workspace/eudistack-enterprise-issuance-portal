package es.in2.issuanceportal.authenticsource.domain;

import java.util.Objects;

/** The outcome of an answer of the source: confirmed with the person's record, or not confirmed. */
public sealed interface EntitlementDecision {

    record Confirmed(SourceRecord record) implements EntitlementDecision {
        public Confirmed {
            Objects.requireNonNull(record, "record");
        }
    }

    record NotConfirmed(NotConfirmedReason reason) implements EntitlementDecision {
        public NotConfirmed {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
