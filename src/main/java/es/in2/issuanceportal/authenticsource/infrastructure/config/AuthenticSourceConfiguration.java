package es.in2.issuanceportal.authenticsource.infrastructure.config;

import es.in2.issuanceportal.authenticsource.application.AdapterRegistry;
import es.in2.issuanceportal.authenticsource.application.port.AuthenticSourceAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Set;

@Slf4j
@Configuration
class AuthenticSourceConfiguration {

    /**
     * Environments (issuance-portal.env) where adapters with synthetic data may run. An allowlist, like
     * the local data key's: any other value, production or a misspelling of it, leaves them out.
     */
    static final Set<String> SYNTHETIC_DATA_ENVIRONMENTS = Set.of("local", "test", "stg");
    /** Adapters with synthetic data: never registered in production, even if a tenant configures them. */
    static final Set<String> NON_PRODUCTION_ADAPTERS = Set.of("simulated");

    @Bean
    AdapterRegistry adapterRegistry(ObjectProvider<AuthenticSourceAdapter> adapters,
                                    @Value("${issuance-portal.env}") String env) {
        return registry(env, adapters.orderedStream().toList());
    }

    static AdapterRegistry registry(String env, List<AuthenticSourceAdapter> adapters) {
        AdapterRegistry registry = new AdapterRegistry(allowedIn(env, adapters));
        log.info("Authentic source adapters {} (env {})", registry.types(), env);
        return registry;
    }

    /** The adapters this environment may use: outside the allowlist, none with synthetic data (design.md AD-3). */
    static List<AuthenticSourceAdapter> allowedIn(String env, List<AuthenticSourceAdapter> adapters) {
        if (env != null && SYNTHETIC_DATA_ENVIRONMENTS.contains(env)) {
            return adapters;
        }
        return adapters.stream().filter(adapter -> !NON_PRODUCTION_ADAPTERS.contains(adapter.type())).toList();
    }
}
