package es.in2.issuanceportal.authenticsource.infrastructure.persistence;

import es.in2.issuanceportal.authenticsource.domain.config.InvalidSourceConfigException;
import es.in2.issuanceportal.authenticsource.domain.config.SourceAuth;
import es.in2.issuanceportal.authenticsource.domain.config.SourceSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticSourceConfigDocumentTest {

    private static AuthenticSourceConfigDocument.SourceDocument source(List<AuthenticSourceConfigDocument.MappingDocument> mapping) {
        return new AuthenticSourceConfigDocument.SourceDocument("DoctorID", "simulated",
                "https://registro.example.org/api", "eudistack/stg/cgcom/registro", 10, List.of("personal_id"), mapping,
                null, null, null, Map.of("people", List.of(Map.of("personal_id", "00000001R"))));
    }

    @Test
    void toDomain_nullMappingElement_namesItsPosition() {
        AuthenticSourceConfigDocument document = new AuthenticSourceConfigDocument("doctorid.sd.1", null,
                Map.of("doctorid.sd.1", source(Arrays.asList(
                        new AuthenticSourceConfigDocument.MappingDocument("a", "b", null), null))), null);

        assertThatThrownBy(document::toDomain)
                .isInstanceOf(InvalidSourceConfigException.class)
                .hasMessage("authentic_source.doctorid.sd.1.mapping[1] is empty");
    }

    @Test
    void toString_document_hidesAddressSecretAndRecords() {
        AuthenticSourceConfigDocument document = new AuthenticSourceConfigDocument("doctorid.sd.1", null,
                Map.of("doctorid.sd.1", source(List.of())), null);

        assertThat(document.toString() + document.authenticSource().get("doctorid.sd.1"))
                .contains("doctorid.sd.1", "simulated")
                .doesNotContain("registro.example.org", "eudistack/stg/cgcom/registro", "00000001R");
    }

    private static final String HTTP_CONFIG = """
            {"attestation_type": "doctorid.sd.1", "identifier_types": {"doctorid.sd.1": "colegiado"},
             "data_placement": {"givenName": "identity.given_name", "medical_board": "authentic_source.medical_board"},
             "authentic_source": {"doctorid.sd.1": {
               "type_name": "DoctorID", "adapter": "http", "url": "https://adapter.example.org/cgcom-adapter",
               "auth": "secret", "secret_ref": "eudistack/stg/cgcom/adapter", "timeout_seconds": 5,
               "identifier_types": ["personal_id"],
               "mapping": [{"attribute": "medical_board", "claim": "medical_board", "label": "Colegio"}],
               "contract": {"lookup_path": "/api/v1/practitioners/eligibility",
                            "dispute_path": "/api/v1/practitioners/data-disputes",
                            "request": {"given_name": "given_name", "family_names": "family_names",
                                        "identifier": "administrative_number"},
                            "dispute_request": {"identifier": "holder.administrative_number",
                                                "reference": "collegiate_number"},
                            "response": {"requests": "requests", "reference": "collegiate_number", "status": "status"},
                            "values": {"status": {"eligible": "available"}}}}}}""";

    private static AuthenticSourceConfigDocument parse(String json) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, AuthenticSourceConfigDocument.class);
    }

    @Test
    @DisplayName("AC-19 — toDomain_httpSource_readsAuthSecretRefAndContract")
    void toDomain_httpSource_readsAuthSecretRefAndContract() throws Exception {
        SourceSettings settings = parse(HTTP_CONFIG).toDomain().source().orElseThrow();

        assertThat(settings.adapter()).isEqualTo("http");
        assertThat(settings.auth()).isEqualTo(SourceAuth.SECRET);
        assertThat(settings.secretRef()).isEqualTo("eudistack/stg/cgcom/adapter");
        assertThat(settings.timeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(settings.contract().lookupPath()).isEqualTo("/api/v1/practitioners/eligibility");
        assertThat(settings.contract().supportsDispute()).isTrue();
        assertThat(settings.contract().translate("status", "eligible")).contains("available");
    }

    @Test
    @DisplayName("ES-05 — toDomain_mappingOntoTheHolderIdentity_isInvalidWithoutQuotingTheValue")
    void toDomain_mappingOntoTheHolderIdentity_isInvalidWithoutQuotingTheValue() throws Exception {
        for (String claim : List.of("givenName", "familyName", "personal_identifier")) {
            AuthenticSourceConfigDocument document = parse(HTTP_CONFIG.replace(
                    "\"claim\": \"medical_board\"", "\"claim\": \"" + claim + "\""));

            assertThatThrownBy(document::toDomain)
                    .isInstanceOf(InvalidSourceConfigException.class)
                    .hasMessage("authentic_source.doctorid.sd.1.mapping[0].claim places source data in the identity");
        }
    }

    @Test
    @DisplayName("ES-05 — toDomain_claimPlacedFromTheIdentityByTheTenant_isInvalid")
    void toDomain_claimPlacedFromTheIdentityByTheTenant_isInvalid() throws Exception {
        AuthenticSourceConfigDocument document = parse(HTTP_CONFIG.replace(
                "\"medical_board\": \"authentic_source.medical_board\"", "\"medical_board\": \"identity.given_name\""));

        assertThatThrownBy(document::toDomain)
                .isInstanceOf(InvalidSourceConfigException.class)
                .hasMessage("authentic_source.doctorid.sd.1.mapping[0].claim places source data in the identity");
    }

    @Test
    @DisplayName("ES-05 — toDomain_wrongHttpConfiguration_namesTheFieldNeverItsValue")
    void toDomain_wrongHttpConfiguration_namesTheFieldNeverItsValue() {
        assertThatThrownBy(() -> parse(HTTP_CONFIG.replace("\"secret_ref\": \"eudistack/stg/cgcom/adapter\",", ""))
                .toDomain()).hasMessage("authentic_source.doctorid.sd.1.secret_ref is required");
        assertThatThrownBy(() -> parse(HTTP_CONFIG.replace("\"timeout_seconds\": 5", "\"timeout_seconds\": 30"))
                .toDomain()).hasMessage("authentic_source.doctorid.sd.1.timeout_seconds must be between 1 and 10");
        assertThatThrownBy(() -> parse(HTTP_CONFIG.replace("\"auth\": \"secret\"", "\"auth\": \"magic\""))
                .toDomain()).hasMessage("authentic_source.doctorid.sd.1.auth is unknown");
        assertThatThrownBy(() -> parse(HTTP_CONFIG.replace(
                "\"url\": \"https://adapter.example.org/cgcom-adapter\",", "")).toDomain())
                .hasMessage("authentic_source.doctorid.sd.1.url is required");
    }

    @Test
    @DisplayName("EUD-359 ES-05 — toDomain_urlThatIsNotAPlainHttpsAddress_isInvalidWithoutQuotingIt")
    void toDomain_urlThatIsNotAPlainHttpsAddress_isInvalidWithoutQuotingIt() throws Exception {
        for (String url : List.of("http://adapter.example.org/cgcom-adapter", "https://user:pw@adapter.example.org/a",
                "https://adapter.example.org/a?next=evil", "https://adapter.example.org/a#x", "https:///no-host",
                "ftp://adapter.example.org", "not a url")) {
            AuthenticSourceConfigDocument document = parse(
                    HTTP_CONFIG.replace("https://adapter.example.org/cgcom-adapter", url));

            assertThatThrownBy(document::toDomain)
                    .isInstanceOf(InvalidSourceConfigException.class)
                    .hasMessageStartingWith("authentic_source.doctorid.sd.1.url ")
                    .hasMessageNotContaining("adapter.example.org");
        }
    }
}
