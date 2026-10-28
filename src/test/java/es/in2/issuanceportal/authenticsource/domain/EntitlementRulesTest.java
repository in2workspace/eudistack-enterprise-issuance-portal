package es.in2.issuanceportal.authenticsource.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EntitlementRulesTest {

    private static final PersonalIdentifier DNI = new PersonalIdentifier(IdentifierType.DNI, "00000001R");
    private static final PersonalIdentifier COLEGIADO = new PersonalIdentifier(IdentifierType.COLEGIADO, "280000001");

    private static SourceRecord record(boolean entitled, PersonalIdentifier... identifiers) {
        return new SourceRecord(List.of(identifiers), entitled, Map.of("medical_board", List.of("Madrid")));
    }

    @Test
    void decide_notFound_isNotConfirmedNotFound() {
        assertThat(EntitlementRules.decide(DNI, LookupResult.notFound()))
                .isEqualTo(new EntitlementDecision.NotConfirmed(NotConfirmedReason.NOT_FOUND));
    }

    @Test
    void decide_samePersonEntitled_isConfirmedWithTheRecord() {
        SourceRecord found = record(true, DNI, COLEGIADO);

        assertThat(EntitlementRules.decide(DNI, LookupResult.found(found)))
                .isEqualTo(new EntitlementDecision.Confirmed(found));
    }

    @Test
    void decide_searchedByColegiado_comparesTheColegiado() {
        SourceRecord found = record(true, DNI, COLEGIADO);

        assertThat(EntitlementRules.decide(COLEGIADO, LookupResult.found(found)))
                .isEqualTo(new EntitlementDecision.Confirmed(found));
    }

    @Test
    void decide_samePersonNotEntitled_isNotConfirmedNotEntitled() {
        assertThat(EntitlementRules.decide(DNI, LookupResult.found(record(false, DNI, COLEGIADO))))
                .isEqualTo(new EntitlementDecision.NotConfirmed(NotConfirmedReason.NOT_ENTITLED));
    }

    @Test
    void decide_anotherPerson_isIdentifierMismatch() {
        PersonalIdentifier other = new PersonalIdentifier(IdentifierType.DNI, "00000002W");

        assertThat(EntitlementRules.decide(DNI, LookupResult.found(record(true, other, COLEGIADO))))
                .isEqualTo(new EntitlementDecision.NotConfirmed(NotConfirmedReason.IDENTIFIER_MISMATCH));
    }

    @Test
    void decide_anotherPersonNotEntitled_isIdentifierMismatchNotNotEntitled() {
        PersonalIdentifier other = new PersonalIdentifier(IdentifierType.DNI, "00000002W");

        assertThat(EntitlementRules.decide(DNI, LookupResult.found(record(false, other))))
                .isEqualTo(new EntitlementDecision.NotConfirmed(NotConfirmedReason.IDENTIFIER_MISMATCH));
    }

    @Test
    void decide_withoutIdentifierOfTheSearchedType_isIdentifierMismatch() {
        assertThat(EntitlementRules.decide(COLEGIADO, LookupResult.found(record(true, DNI))))
                .isEqualTo(new EntitlementDecision.NotConfirmed(NotConfirmedReason.IDENTIFIER_MISMATCH));
    }

    @Test
    void decide_sameValueOfAnotherType_isIdentifierMismatch() {
        PersonalIdentifier dniWithColegiadoValue = new PersonalIdentifier(IdentifierType.DNI, "280000001");

        assertThat(EntitlementRules.decide(COLEGIADO, LookupResult.found(record(true, dniWithColegiadoValue))))
                .isEqualTo(new EntitlementDecision.NotConfirmed(NotConfirmedReason.IDENTIFIER_MISMATCH));
    }

    @Test
    void decide_identifierWithOtherCaseAndSpaces_isTheSamePerson() {
        PersonalIdentifier lowerCase = new PersonalIdentifier(IdentifierType.DNI, "00000001r");
        SourceRecord found = record(true, new PersonalIdentifier(IdentifierType.DNI, " 0000 0001R "));

        assertThat(EntitlementRules.decide(lowerCase, LookupResult.found(found)))
                .isEqualTo(new EntitlementDecision.Confirmed(found));
    }
}
