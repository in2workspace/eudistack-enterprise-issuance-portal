package es.in2.issuanceportal.authenticsource.infrastructure.persistence;

import es.in2.issuanceportal.authenticsource.domain.config.InvalidSourceConfigException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticSourceConfigDocumentTest {

    private static AuthenticSourceConfigDocument.SourceDocument source(List<AuthenticSourceConfigDocument.MappingDocument> mapping) {
        return new AuthenticSourceConfigDocument.SourceDocument("DoctorID", "simulated",
                "https://registro.example.org/api", "eudistack/stg/cgcom/registro", 10, List.of("dni"), mapping,
                null, Map.of("records", List.of(Map.of("dni", "00000001R"))));
    }

    @Test
    void toDomain_nullMappingElement_namesItsPosition() {
        AuthenticSourceConfigDocument document = new AuthenticSourceConfigDocument("doctorid.sd.1", null,
                Map.of("doctorid.sd.1", source(Arrays.asList(
                        new AuthenticSourceConfigDocument.MappingDocument("a", "b", null), null))));

        assertThatThrownBy(document::toDomain)
                .isInstanceOf(InvalidSourceConfigException.class)
                .hasMessage("authentic_source.doctorid.sd.1.mapping[1] is empty");
    }

    @Test
    void toString_document_hidesAddressSecretAndRecords() {
        AuthenticSourceConfigDocument document = new AuthenticSourceConfigDocument("doctorid.sd.1", null,
                Map.of("doctorid.sd.1", source(List.of())));

        assertThat(document.toString() + document.authenticSource().get("doctorid.sd.1"))
                .contains("doctorid.sd.1", "simulated")
                .doesNotContain("registro.example.org", "eudistack/stg/cgcom/registro", "00000001R");
    }
}
