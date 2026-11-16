package es.in2.issuanceportal.authenticsource.domain.config;

import es.in2.issuanceportal.authenticsource.domain.IdentifierType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SourceContractTest {

    private static final Map<String, String> REQUEST = Map.of("identifier", "administrative_number");
    private static final Map<String, String> RESPONSE = Map.of("requests", "requests", "reference", "ref",
            "status", "status");

    private static SourceContract contract(String lookupPath, String disputePath, Map<String, String> request,
                                           Map<String, String> disputeRequest, Map<String, String> response,
                                           Map<String, Map<String, String>> values) {
        return new SourceContract(lookupPath, disputePath, request, disputeRequest, response, values);
    }

    @Test
    void constructor_minimalContract_isValidAndDoesNotSupportDispute() {
        SourceContract contract = contract("/lookup", null, REQUEST, null, RESPONSE, null);

        assertThat(contract.supportsDispute()).isFalse();
        assertThat(contract.responseName("reason")).isEmpty();
        assertThat(contract.translate("status", "available")).contains("available");
    }

    @Test
    void translate_groupWithEquivalences_isStrict() {
        SourceContract contract = contract("/lookup", null, REQUEST, null, RESPONSE,
                Map.of("status", Map.of("eligible", "available")));

        assertThat(contract.translate("status", "eligible")).contains("available");
        assertThat(contract.translate("status", "available")).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("ES-05 — constructor_wrongContract_namesTheFieldNeverTheValue")
    void constructor_wrongContract_namesTheFieldNeverTheValue() {
        Map<String, String> unknownRequest = new HashMap<>(REQUEST);
        unknownRequest.put("shoe_size", "secret-name");
        Map<String, String> noStatus = Map.of("requests", "requests", "reference", "ref");

        assertThatThrownBy(() -> contract("secret-path", null, REQUEST, null, RESPONSE, null))
                .hasMessage("contract.lookup_path must be a path starting with /");
        assertThatThrownBy(() -> contract("/lookup", null, unknownRequest, null, RESPONSE, null))
                .hasMessage("contract.request has an unknown field");
        assertThatThrownBy(() -> contract("/lookup", null, Map.of("given_name", "g"), null, RESPONSE, null))
                .hasMessage("contract.request.identifier is required");
        assertThatThrownBy(() -> contract("/lookup", null, REQUEST, null, noStatus, null))
                .hasMessage("contract.response.status is required");
        assertThatThrownBy(() -> contract("/lookup", "/dispute", REQUEST, REQUEST, RESPONSE, null))
                .hasMessage("contract.dispute_request.reference is required");
        assertThatThrownBy(() -> contract("/lookup", null, REQUEST, null, RESPONSE,
                Map.of("status", Map.of("eligible", "secret-status"))))
                .hasMessage("contract.values.status has an unknown value");
        assertThatThrownBy(() -> contract("/lookup", null, REQUEST, null, RESPONSE,
                Map.of("status", Map.of("eligible", "under_review"))))
                .hasMessage("contract.values.status has an unknown value");
        assertThatThrownBy(() -> contract("/lookup", null, REQUEST, null, RESPONSE,
                Map.of("colour", Map.of("a", "b")))).hasMessage("contract.values has an unknown group");
    }

    @Test
    @DisplayName("ES-05 — constructor_httpSourceWithoutWhatItNeeds_namesTheField")
    void constructor_httpSourceWithoutWhatItNeeds_namesTheField() {
        SourceContract contract = contract("/lookup", null, REQUEST, null, RESPONSE, null);
        Set<IdentifierType> types = Set.of(IdentifierType.PERSONAL_ID);

        assertThatThrownBy(() -> new SourceSettings("T", "http", null, null, null, types, List.of(), null,
                SourceAuth.SYSTEM_TOKEN, contract, null)).hasMessage("url is required");
        assertThatThrownBy(() -> new SourceSettings("T", "http", "https://x.example", null, null, types, List.of(),
                null, null, contract, null)).hasMessage("auth is required");
        assertThatThrownBy(() -> new SourceSettings("T", "http", "https://x.example", " ", null, types, List.of(),
                null, SourceAuth.SECRET, contract, null)).hasMessage("secret_ref is required");
        assertThatThrownBy(() -> new SourceSettings("T", "http", "https://x.example", null, null, types, List.of(),
                null, SourceAuth.SYSTEM_TOKEN, null, null)).hasMessage("contract is required");
        assertThatThrownBy(() -> new SourceSettings("T", "http", "https://x.example", null, Duration.ofSeconds(11),
                types, List.of(), null, SourceAuth.SYSTEM_TOKEN, contract, null))
                .hasMessage("timeout_seconds must be between 1 and 10");
    }
}
