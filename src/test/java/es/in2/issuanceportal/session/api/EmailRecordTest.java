package es.in2.issuanceportal.session.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailRecordTest {

    private static final String ADDRESS = "lucia.prueba@example.org";

    @Test
    void fromJson_writtenRecord_readsTheSame() {
        assertThat(EmailRecord.fromJson(new EmailRecord(ADDRESS).toJson())).isEqualTo(new EmailRecord(ADDRESS));
    }

    @Test
    void toJson_record_usesTheContractName() {
        assertThat(new EmailRecord(ADDRESS).toJson()).isEqualTo("{\"email\":\"" + ADDRESS + "\"}");
    }

    @Test
    void toString_record_hidesTheAddress() {
        assertThat(new EmailRecord(ADDRESS).toString()).doesNotContain(ADDRESS, "lucia");
        assertThat(new EmailRecord.Document(ADDRESS).toString()).doesNotContain(ADDRESS, "lucia");
    }

    @Test
    void fromJson_withoutEmail_namesTheField() {
        assertInvalid("{}");
    }

    @Test
    void fromJson_blankEmail_namesTheField() {
        assertInvalid("{\"email\":\" \"}");
    }

    private static void assertInvalid(String json) {
        assertThatThrownBy(() -> EmailRecord.fromJson(json))
                .isInstanceOf(InvalidSectionException.class)
                .extracting(e -> ((InvalidSectionException) e).field()).isEqualTo("email");
    }
}
