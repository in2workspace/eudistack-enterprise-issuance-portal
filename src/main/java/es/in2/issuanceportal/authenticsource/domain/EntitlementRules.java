package es.in2.issuanceportal.authenticsource.domain;

import java.util.Optional;

/**
 * The rules of design.md §3.3 for an answer of the source, the same for every adapter. A source that
 * does not answer never reaches them: that is an unavailable source, not a decision.
 */
public final class EntitlementRules {

    private EntitlementRules() {
    }

    /**
     * Decides on the answer to a search by {@code searched}. The identity is checked before the
     * entitlement: the record of another person says nothing about the holder.
     */
    public static EntitlementDecision decide(PersonalIdentifier searched, LookupResult result) {
        return switch (result) {
            case LookupResult.NotFound _ -> new EntitlementDecision.NotConfirmed(NotConfirmedReason.NOT_FOUND);
            case LookupResult.Found(SourceRecord found) -> decideOn(searched, found);
        };
    }

    private static EntitlementDecision decideOn(PersonalIdentifier searched, SourceRecord found) {
        Optional<PersonalIdentifier> returned = found.identifierOf(searched.type());
        if (returned.isEmpty() || !returned.get().sameAs(searched)) {
            return new EntitlementDecision.NotConfirmed(NotConfirmedReason.IDENTIFIER_MISMATCH);
        }
        if (!found.entitled()) {
            return new EntitlementDecision.NotConfirmed(NotConfirmedReason.NOT_ENTITLED);
        }
        return new EntitlementDecision.Confirmed(found);
    }
}
