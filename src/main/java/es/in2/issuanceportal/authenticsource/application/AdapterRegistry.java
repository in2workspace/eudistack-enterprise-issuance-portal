package es.in2.issuanceportal.authenticsource.application;

import es.in2.issuanceportal.authenticsource.application.port.AuthenticSourceAdapter;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The adapters available in this deployment, by the name the configuration uses. Adapted from
 * {@code DataAcquisitionProviderRegistryImpl} of {@code eudistack-enterprise-backend}. A name used twice
 * stops the service from starting; a name nobody registered is the tenant's configuration problem.
 */
public final class AdapterRegistry {

    private final Map<String, AuthenticSourceAdapter> adapters;

    public AdapterRegistry(Collection<AuthenticSourceAdapter> adapters) {
        this.adapters = adapters.stream().collect(Collectors.toUnmodifiableMap(
                AuthenticSourceAdapter::type, Function.identity(), (first, second) -> {
                    throw new IllegalStateException("Two authentic source adapters named '" + first.type() + "'");
                }));
    }

    public Optional<AuthenticSourceAdapter> find(String type) {
        return Optional.ofNullable(adapters.get(type));
    }

    public Set<String> types() {
        return adapters.keySet();
    }
}
