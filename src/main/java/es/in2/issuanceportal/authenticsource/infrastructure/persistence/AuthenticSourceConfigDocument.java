package es.in2.issuanceportal.authenticsource.infrastructure.persistence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import es.in2.issuanceportal.authenticsource.domain.IdentifierType;
import es.in2.issuanceportal.authenticsource.domain.config.AttributeMapping;
import es.in2.issuanceportal.authenticsource.domain.config.AuthenticSourceConfig;
import es.in2.issuanceportal.authenticsource.domain.config.InvalidSourceConfigException;
import es.in2.issuanceportal.authenticsource.domain.config.SourceSettings;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
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
        @JsonProperty("authentic_source") Map<String, SourceDocument> authenticSource) {

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
                sources.put(type, prefixed("authentic_source." + type, source));
            });
        }
        return new AuthenticSourceConfig(attestationType, loginTypes, sources);
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
            @JsonProperty("settings") Map<String, Object> settings) {

        SourceSettings toDomain() {
            return new SourceSettings(typeName, adapter, url, secretRef,
                    timeoutSeconds == null ? null : Duration.ofSeconds(timeoutSeconds),
                    identifierTypes == null ? null : types(identifierTypes, "identifier_types"),
                    mapping == null ? null : translatedMapping(),
                    emailAttribute, settings);
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
