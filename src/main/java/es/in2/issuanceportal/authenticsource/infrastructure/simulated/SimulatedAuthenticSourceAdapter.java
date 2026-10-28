package es.in2.issuanceportal.authenticsource.infrastructure.simulated;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.issuanceportal.authenticsource.application.port.AuthenticSourceAdapter;
import es.in2.issuanceportal.authenticsource.application.port.AuthenticSourceConfigRepository;
import es.in2.issuanceportal.authenticsource.domain.IdentifierType;
import es.in2.issuanceportal.authenticsource.domain.LookupResult;
import es.in2.issuanceportal.authenticsource.domain.PersonalIdentifier;
import es.in2.issuanceportal.authenticsource.domain.SourceRecord;
import es.in2.issuanceportal.authenticsource.domain.config.SourceSettings;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A source with synthetic people, read from the tenant's configuration (design.md AD-3), so the journey
 * can be built and tested before the tenant's real source is available. Each record is found by its DNI
 * or NIE and by its registration number. A record can be made slow or failing to try the unavailable
 * source. Never registered in production (AuthenticSourceConfiguration).
 *
 * <p>{@code settings.records} of the tenant's source:
 * {@code [{"dni", "colegiado", "entitled", "attributes": {...}, "delay_seconds", "fail"}]}.
 */
@Component
class SimulatedAuthenticSourceAdapter implements AuthenticSourceAdapter {

    static final String TYPE = "simulated";

    private final AuthenticSourceConfigRepository configs;
    private final ObjectMapper json;

    SimulatedAuthenticSourceAdapter(AuthenticSourceConfigRepository configs, ObjectMapper json) {
        this.configs = configs;
        this.json = json;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Set<IdentifierType> supportedIdentifierTypes() {
        return Set.of(IdentifierType.DNI, IdentifierType.COLEGIADO);
    }

    @Override
    public LookupResult lookup(TenantId tenant, String attestationType, PersonalIdentifier identifier,
                               Duration timeout) {
        for (SimulatedRecord simulated : records(tenant, attestationType)) {
            SourceRecord found = simulated.toSourceRecord();
            boolean matches = found.identifierOf(identifier.type()).map(identifier::sameAs).orElse(false);
            if (matches) {
                return answer(simulated, found);
            }
        }
        return LookupResult.notFound();
    }

    private static LookupResult answer(SimulatedRecord simulated, SourceRecord found) {
        if (simulated.delaySeconds() != null && simulated.delaySeconds() > 0) {
            try {
                Thread.sleep(Duration.ofSeconds(simulated.delaySeconds()));
            } catch (InterruptedException e) {
                // The module stopped waiting: give up quietly, nobody reads this answer.
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Simulated search interrupted", e);
            }
        }
        if (Boolean.TRUE.equals(simulated.fail())) {
            throw new IllegalStateException("Simulated source failure");
        }
        return LookupResult.found(found);
    }

    /**
     * The records of the tenant's source, read again here because the port only carries the tenant and the
     * type. If the configuration changed since the module checked it and no longer chooses this adapter,
     * the search fails (an unavailable source) rather than answer with another configuration's people.
     */
    private List<SimulatedRecord> records(TenantId tenant, String attestationType) {
        SourceSettings settings = configs.find(tenant)
                .flatMap(config -> Optional.ofNullable(config.sources().get(attestationType)))
                .filter(source -> TYPE.equals(source.adapter()))
                .orElseThrow(() -> new IllegalStateException("No simulated source configured"));
        Object records = settings.adapterSettings().get("records");
        if (records == null) {
            return List.of();
        }
        try {
            return List.of(json.convertValue(records, SimulatedRecord[].class));
        } catch (IllegalArgumentException _) {
            // Jackson's message may quote a record: name the setting only.
            throw new IllegalStateException("settings.records of the simulated source is invalid");
        }
    }

    /** One synthetic person. Its text form carries no data. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SimulatedRecord(@JsonProperty("dni") String dni,
                           @JsonProperty("colegiado") String colegiado,
                           @JsonProperty("entitled") boolean entitled,
                           @JsonProperty("attributes") Map<String, Object> attributes,
                           @JsonProperty("delay_seconds") Integer delaySeconds,
                           @JsonProperty("fail") Boolean fail) {

        SourceRecord toSourceRecord() {
            List<PersonalIdentifier> identifiers = new ArrayList<>();
            if (dni != null && !dni.isBlank()) {
                identifiers.add(new PersonalIdentifier(IdentifierType.DNI, dni));
            }
            if (colegiado != null && !colegiado.isBlank()) {
                identifiers.add(new PersonalIdentifier(IdentifierType.COLEGIADO, colegiado));
            }
            return new SourceRecord(identifiers, entitled, attributes);
        }

        @Override
        public String toString() {
            return "SimulatedRecord[redacted]";
        }
    }
}
