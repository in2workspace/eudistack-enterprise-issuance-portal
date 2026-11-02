package es.in2.issuanceportal.authenticsource.domain;

import java.util.Objects;

/** What a search in the authentic source finds: the person's record, or nobody. */
public sealed interface LookupResult {

    static LookupResult found(SourceRecord source) {
        return new Found(source);
    }

    static LookupResult notFound() {
        return new NotFound();
    }

    record Found(SourceRecord source) implements LookupResult {
        public Found {
            Objects.requireNonNull(source, "source");
        }
    }

    record NotFound() implements LookupResult {
    }
}
