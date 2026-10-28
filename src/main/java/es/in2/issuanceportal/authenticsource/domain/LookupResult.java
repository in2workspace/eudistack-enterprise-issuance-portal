package es.in2.issuanceportal.authenticsource.domain;

import java.util.Objects;

/** What a search in the authentic source finds: the person's record, or nobody. */
public sealed interface LookupResult {

    static LookupResult found(SourceRecord record) {
        return new Found(record);
    }

    static LookupResult notFound() {
        return new NotFound();
    }

    record Found(SourceRecord record) implements LookupResult {
        public Found {
            Objects.requireNonNull(record, "record");
        }
    }

    record NotFound() implements LookupResult {
    }
}
