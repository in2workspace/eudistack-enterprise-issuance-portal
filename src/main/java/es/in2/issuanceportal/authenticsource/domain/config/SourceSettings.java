package es.in2.issuanceportal.authenticsource.domain.config;

import es.in2.issuanceportal.authenticsource.domain.IdentifierType;

import java.time.Duration;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The source of one attestation type of a tenant (design.md AD-2), adapted from {@code Source} of
 * {@code DataAcquisitionProperties} in {@code eudistack-enterprise-backend}.
 *
 * @param typeName        name of the attestation type in the preview
 * @param adapter         name of the adapter that talks to the source
 * @param url             address of the source; the simulated adapter has none
 * @param secretRef       reference to its credentials in AWS Secrets Manager; never the secret itself
 * @param timeout         how long the source has to answer, at most 10 seconds
 * @param identifierTypes the identifier types the source is searched by
 * @param mapping         source attribute → attestation claim and label
 * @param emailAttribute  the source's attribute with the holder's email, if it has one
 * @param auth            how the HTTP adapter authenticates to the source; only with {@code http}
 * @param contract        the names and values of the HTTP source; only with {@code http}
 * @param adapterSettings what only the adapter reads, e.g. the people of the simulated one
 */
public record SourceSettings(String typeName, String adapter, String url, String secretRef, Duration timeout,
                             Set<IdentifierType> identifierTypes, List<AttributeMapping> mapping,
                             String emailAttribute, SourceAuth auth, SourceContract contract,
                             Map<String, Object> adapterSettings) {

    public static final Duration MAX_TIMEOUT = Duration.ofSeconds(10);
    /** The adapter of design.md AD-4: its configuration needs the address, the authentication and the contract. */
    public static final String HTTP_ADAPTER = "http";

    /** A source without authentication nor contract, e.g. the simulated one. */
    public SourceSettings(String typeName, String adapter, String url, String secretRef, Duration timeout,
                          Set<IdentifierType> identifierTypes, List<AttributeMapping> mapping,
                          String emailAttribute, Map<String, Object> adapterSettings) {
        this(typeName, adapter, url, secretRef, timeout, identifierTypes, mapping, emailAttribute, null, null,
                adapterSettings);
    }

    public SourceSettings {
        if (typeName == null || typeName.isBlank()) {
            throw new InvalidSourceConfigException("type_name is required");
        }
        if (adapter == null || adapter.isBlank()) {
            throw new InvalidSourceConfigException("adapter is required");
        }
        timeout = timeout == null ? MAX_TIMEOUT : timeout;
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new InvalidSourceConfigException("timeout_seconds must be between 1 and 10");
        }
        if (identifierTypes == null || identifierTypes.isEmpty()) {
            throw new InvalidSourceConfigException("identifier_types is required");
        }
        identifierTypes = Collections.unmodifiableSet(EnumSet.copyOf(identifierTypes));
        mapping = mapping == null ? List.of() : List.copyOf(mapping);
        requireDistinctClaims(mapping);
        emailAttribute = emailAttribute == null || emailAttribute.isBlank() ? null : emailAttribute;
        if (HTTP_ADAPTER.equals(adapter)) {
            requireHttpFields(url, secretRef, auth, contract);
        }
        adapterSettings = adapterSettings == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(adapterSettings));
    }

    private static void requireHttpFields(String url, String secretRef, SourceAuth auth, SourceContract contract) {
        if (url == null || url.isBlank()) {
            throw new InvalidSourceConfigException("url is required");
        }
        if (auth == null) {
            throw new InvalidSourceConfigException("auth is required");
        }
        if (auth == SourceAuth.SECRET && (secretRef == null || secretRef.isBlank())) {
            throw new InvalidSourceConfigException("secret_ref is required");
        }
        if (contract == null) {
            throw new InvalidSourceConfigException("contract is required");
        }
    }

    private static void requireDistinctClaims(List<AttributeMapping> mapping) {
        if (mapping.stream().map(AttributeMapping::claim).distinct().count() != mapping.size()) {
            throw new InvalidSourceConfigException("mapping has a claim twice");
        }
    }

    @Override
    public String toString() {
        return "SourceSettings[typeName=" + typeName + ", adapter=" + adapter + ", timeout=" + timeout
                + ", identifierTypes=" + identifierTypes + ", claims=" + mapping.stream().map(AttributeMapping::claim)
                .toList() + "]";
    }
}
