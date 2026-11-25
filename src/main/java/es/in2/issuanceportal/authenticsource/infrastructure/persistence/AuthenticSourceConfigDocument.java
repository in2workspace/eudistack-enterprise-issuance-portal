package es.in2.issuanceportal.authenticsource.infrastructure.persistence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import es.in2.issuanceportal.authenticsource.domain.IdentifierType;
import es.in2.issuanceportal.authenticsource.domain.config.AttributeMapping;
import es.in2.issuanceportal.authenticsource.domain.config.AuthenticSourceConfig;
import es.in2.issuanceportal.authenticsource.domain.config.InvalidSourceConfigException;
import es.in2.issuanceportal.authenticsource.domain.config.SourceAuth;
import es.in2.issuanceportal.authenticsource.domain.config.SourceContract;
import es.in2.issuanceportal.authenticsource.domain.config.SourceSettings;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What the module reads of {@code portal_config.config}: snake_case, everything else ignored. The rest of
 * the document belongs to the Journey module.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record AuthenticSourceConfigDocument(
        @JsonProperty("attestation_type") String attestationType,
        @JsonProperty("identifier_types") Map<String, String> identifierTypes,
        @JsonProperty("authentic_source") Map<String, SourceDocument> authenticSource,
        @JsonProperty("data_placement") Map<String, String> dataPlacement) {

    /** What the attestation always takes from the journey's identity, whatever the placement says. */
    private static final Set<String> IDENTITY_CLAIMS = Set.of("given_name", "givenName", "family_name",
            "familyName", "family_names", "personal_identifier");
    private static final String IDENTITY_PLACEMENT = "identity.";

    /** Builds the domain configuration, which validates it. */
    AuthenticSourceConfig toDomain() {
        Set<IdentifierType> loginTypes = identifierTypes == null ? Set.of()
                : types(identifierTypes.values(), "identifier_types");
        Map<String, SourceSettings> sources = new LinkedHashMap<>();
        if (authenticSource != null) {
            authenticSource.forEach((type, source) -> {
                if (source == null) {
                    throw new InvalidSourceConfigException("authentic_source." + type + " is empty");
                }
                SourceSettings settings = prefixed("authentic_source." + type, source);
                rejectIdentityClaims("authentic_source." + type, settings);
                sources.put(type, settings);
            });
        }
        return new AuthenticSourceConfig(attestationType, loginTypes, sources);
    }

    /**
     * The name, the family names and the personal identifier of the attestation come from the journey's
     * identity (design.md §3.4): a mapping that puts source data in one of them is a broken configuration.
     */
    private void rejectIdentityClaims(String path, SourceSettings settings) {
        Set<String> identity = new HashSet<>(IDENTITY_CLAIMS);
        if (dataPlacement != null) {
            dataPlacement.forEach((claim, target) -> {
                if (target != null && target.startsWith(IDENTITY_PLACEMENT)) {
                    identity.add(claim);
                }
            });
        }
        for (int i = 0; i < settings.mapping().size(); i++) {
            if (identity.contains(settings.mapping().get(i).claim())) {
                throw new InvalidSourceConfigException(path + ".mapping[" + i
                        + "].claim places source data in the identity");
            }
        }
    }

    private static SourceSettings prefixed(String path, SourceDocument source) {
        try {
            return source.toDomain();
        } catch (InvalidSourceConfigException e) {
            throw new InvalidSourceConfigException(path + "." + e.getMessage());
        }
    }

    static Set<IdentifierType> types(Collection<String> codes, String field) {
        return codes.stream()
                .map(code -> Arrays.stream(IdentifierType.values())
                        .filter(type -> type.code().equals(code))
                        .findFirst()
                        .orElseThrow(() -> new InvalidSourceConfigException(field + " has an unknown type")))
                .collect(Collectors.toSet());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SourceDocument(
            @JsonProperty("type_name") String typeName,
            @JsonProperty("adapter") String adapter,
            @JsonProperty("url") String url,
            @JsonProperty("secret_ref") String secretRef,
            @JsonProperty("timeout_seconds") Integer timeoutSeconds,
            @JsonProperty("identifier_types") List<String> identifierTypes,
            @JsonProperty("mapping") List<MappingDocument> mapping,
            @JsonProperty("email_attribute") String emailAttribute,
            @JsonProperty("auth") String auth,
            @JsonProperty("contract") ContractDocument contract,
            @JsonProperty("settings") Map<String, Object> settings) {

        SourceSettings toDomain() {
            return new SourceSettings(typeName, adapter, url, secretRef,
                    timeoutSeconds == null ? null : Duration.ofSeconds(timeoutSeconds),
                    identifierTypes == null ? null : types(identifierTypes, "identifier_types"),
                    mapping == null ? null : translatedMapping(),
                    emailAttribute, parsedAuth(), contract == null ? null : contract.toDomain(), settings);
        }

        private SourceAuth parsedAuth() {
            if (auth == null) {
                return null;
            }
            return SourceAuth.fromCode(auth).orElseThrow(() -> new InvalidSourceConfigException("auth is unknown"));
        }

        /** An empty element of the list is a broken configuration, not a missing attribute. */
        private List<AttributeMapping> translatedMapping() {
            List<AttributeMapping> translated = new ArrayList<>();
            for (int i = 0; i < mapping.size(); i++) {
                if (mapping.get(i) == null) {
                    throw new InvalidSourceConfigException("mapping[" + i + "] is empty");
                }
                translated.add(mapping.get(i).toDomain());
            }
            return translated;
        }

        /** The address, the secret reference and the adapter's settings (synthetic people) stay out. */
        @Override
        public String toString() {
            return "SourceDocument[adapter=" + adapter + ", redacted]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContractDocument(
            @JsonProperty("lookup_path") String lookupPath,
            @JsonProperty("dispute_path") String disputePath,
            @JsonProperty("request") Map<String, String> request,
            @JsonProperty("dispute_request") Map<String, String> disputeRequest,
            @JsonProperty("response") Map<String, String> response,
            @JsonProperty("values") Map<String, Map<String, String>> values) {

        SourceContract toDomain() {
            return new SourceContract(lookupPath, disputePath, request, disputeRequest, response, values);
        }

        @Override
        public String toString() {
            return "ContractDocument[redacted]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MappingDocument(@JsonProperty("attribute") String attribute, @JsonProperty("claim") String claim,
                           @JsonProperty("label") String label) {

        AttributeMapping toDomain() {
            return new AttributeMapping(attribute, claim, label);
        }
    }

    @Override
    public String toString() {
        return "AuthenticSourceConfigDocument[attestationType=" + attestationType + ", sources="
                + (authenticSource == null ? "[]" : authenticSource.keySet()) + "]";
    }
}
