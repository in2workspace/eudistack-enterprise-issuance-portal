package es.in2.issuanceportal.session.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HolderIdentityTest {

    private static final HolderIdentity IDENTITY = new HolderIdentity("Lucía", "Prueba Sintética",
            new PersonalIdentifier(IdentifierType.DNI, "00000001R"), "lucia@example.org");

    @Test
    void toJson_identity_usesTheContractNames() {
        assertThat(IDENTITY.toJson()).isEqualTo("{\"given_name\":\"Lucía\",\"family_name\":\"Prueba Sintética\","
                + "\"personal_identifier\":{\"type\":\"dni\",\"value\":\"00000001R\"},"
                + "\"email\":\"lucia@example.org\"}");
    }

    @Test
    void fromJson_writtenIdentity_readsTheSame() {
        assertThat(HolderIdentity.fromJson(IDENTITY.toJson())).isEqualTo(IDENTITY);
    }

    @Test
    void fromJson_withoutEmail_hasNoEmail() {
        HolderIdentity identity = HolderIdentity.fromJson("{\"given_name\":\"Lucía\",\"family_name\":\"Prueba\","
                + "\"personal_identifier\":{\"type\":\"colegiado\",\"value\":\"280000001\"}}");

        assertThat(identity.emailIfPresent()).isEmpty();
        assertThat(identity.personalIdentifier())
                .isEqualTo(new PersonalIdentifier(IdentifierType.COLEGIADO, "280000001"));
    }

    @Test
    void toJson_withoutEmail_leavesItOut() {
        HolderIdentity identity = new HolderIdentity("Lucía", "Prueba",
                new PersonalIdentifier(IdentifierType.DNI, "00000001R"), " ");

        assertThat(identity.toJson()).doesNotContain("email");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "{\"family_name\":\"P\",\"personal_identifier\":{\"type\":\"dni\",\"value\":\"1\"}}|given_name",
        "{\"given_name\":\"L\",\"family_name\":\" \",\"personal_identifier\":{\"type\":\"dni\",\"value\":\"1\"}}"
                + "|family_name",
        "{\"given_name\":\"L\",\"family_name\":\"P\"}|personal_identifier",
        "{\"given_name\":\"L\",\"family_name\":\"P\",\"personal_identifier\":{\"type\":\"nif\",\"value\":\"1\"}}"
                + "|personal_identifier.type",
        "{\"given_name\":\"L\",\"family_name\":\"P\",\"personal_identifier\":{\"type\":\"dni\",\"value\":\"\"}}"
                + "|personal_identifier.value",
        "{\"given_name\":\"L\",\"family_name\":\"P\",\"personal_identifier\":\"00000001R\"}|personal_identifier",
        "not json|$"
    })
    void fromJson_invalidShape_namesTheField(String json, String field) {
        assertThatThrownBy(() -> HolderIdentity.fromJson(json))
                .isInstanceOfSatisfying(InvalidSectionException.class, e -> assertThat(e.field()).isEqualTo(field));
    }

    @Test
    void fromJson_invalidShape_neverQuotesTheContent() {
        String json = "{\"given_name\":\"L\",\"family_name\":\"P\",\"personal_identifier\":\"00000001R\"}";

        assertThatThrownBy(() -> HolderIdentity.fromJson(json))
                .satisfies(e -> assertThat(e).hasStackTraceContaining("personal_identifier"))
                .satisfies(e -> assertThat(stackTrace(e)).doesNotContain("00000001R"));
    }

    @Test
    void toString_identity_hidesPersonalData() {
        assertThat(IDENTITY.toString())
                .contains("dni")
                .doesNotContain("00000001R", "Lucía", "Prueba", "lucia@example.org");
    }

    private static String stackTrace(Throwable e) {
        StringWriter out = new StringWriter();
        e.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    @Test
    void toString_jsonDocuments_hidePersonalData() {
        HolderIdentity.Document document = new HolderIdentity.Document("Lucía", "Prueba",
                new HolderIdentity.IdentifierDocument("dni", "00000001R"), "lucia@example.org");

        assertThat(document + " " + document.personalIdentifier())
                .contains("dni")
                .doesNotContain("Lucía", "Prueba", "00000001R", "lucia@example.org");
    }
}
