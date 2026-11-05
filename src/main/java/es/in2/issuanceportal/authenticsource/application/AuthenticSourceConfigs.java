package es.in2.issuanceportal.authenticsource.application;

import es.in2.issuanceportal.authenticsource.application.port.AuthenticSourceAdapter;
import es.in2.issuanceportal.authenticsource.application.port.AuthenticSourceConfigRepository;
import es.in2.issuanceportal.authenticsource.domain.IdentifierType;
import es.in2.issuanceportal.authenticsource.domain.config.AuthenticSourceConfig;
import es.in2.issuanceportal.authenticsource.domain.config.SourceSettings;
import es.in2.issuanceportal.shared.error.ProblemCode;
import es.in2.issuanceportal.shared.error.ProblemException;
import es.in2.issuanceportal.shared.tenant.TenantId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;

/**
 * The tenant's source for the journey's attestation type, checked against the adapters of this
 * deployment before any search (ac.md decision 6). A configuration that cannot be honoured answers
 * {@code config_unavailable}: it fails when it is loaded, never half-way through a search.
 */
@Slf4j
@Service
public class AuthenticSourceConfigs {

    private final AuthenticSourceConfigRepository configs;
    private final AdapterRegistry adapters;

    public AuthenticSourceConfigs(AuthenticSourceConfigRepository configs, AdapterRegistry adapters) {
        this.configs = configs;
        this.adapters = adapters;
    }

    public Current current(TenantId tenant) {
        AuthenticSourceConfig config = configs.find(tenant)
                .orElseThrow(() -> new ProblemException(ProblemCode.CONFIG_UNAVAILABLE));
        Optional<SourceSettings> source = config.source();
        if (source.isEmpty()) {
            return new Current(config.attestationType(), null, null);
        }
        SourceSettings settings = source.get();
        AuthenticSourceAdapter adapter = adapters.find(settings.adapter())
                .orElseThrow(() -> invalid(tenant, config, "adapter is not available in this environment"));
        if (!adapter.supportedIdentifierTypes().containsAll(settings.identifierTypes())) {
            throw invalid(tenant, config, "identifier_types has a type the adapter cannot search by");
        }
        if (!settings.identifierTypes().containsAll(config.loginIdentifierTypes())) {
            throw invalid(tenant, config, "identifier_types does not cover every type the login can give");
        }
        return new Current(config.attestationType(), settings, adapter);
    }

    private static ProblemException invalid(TenantId tenant, AuthenticSourceConfig config, String problem) {
        log.warn("Authentic source configuration of tenant {} for {} is invalid: authentic_source.{}",
                tenant, config.attestationType(), problem);
        return new ProblemException(ProblemCode.CONFIG_UNAVAILABLE);
    }

    /**
     * The journey's attestation type and its source, if it has one.
     *
     * @param settings {@code null} when the attestation type has no source
     * @param adapter  {@code null} when the attestation type has no source
     */
    public record Current(String attestationType, SourceSettings settings, AuthenticSourceAdapter adapter) {

        public boolean hasSource() {
            return settings != null;
        }

        public Set<IdentifierType> identifierTypes() {
            return hasSource() ? settings.identifierTypes() : Set.of();
        }
    }
}
