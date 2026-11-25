package es.in2.issuanceportal.authenticsource.infrastructure.simulated;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.issuanceportal.authenticsource.application.port.AuthenticSourceAdapter;
import es.in2.issuanceportal.authenticsource.application.port.DisputeNotSupportedException;
import es.in2.issuanceportal.authenticsource.domain.Holder;
import es.in2.issuanceportal.authenticsource.domain.IdentifierType;
import es.in2.issuanceportal.authenticsource.domain.MismatchedField;
import es.in2.issuanceportal.authenticsource.domain.PersonalIdentifier;
import es.in2.issuanceportal.authenticsource.domain.RequestStatus;
import es.in2.issuanceportal.authenticsource.domain.SourceRequest;
import es.in2.issuanceportal.authenticsource.domain.SourceRequests;
import es.in2.issuanceportal.authenticsource.domain.config.SourceSettings;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A source with synthetic people, read from the tenant's configuration (design.md AD-3), so the journey
 * can be built and tested before the tenant's real source is available. A person is found by the type and
 * value of any of their identifiers; the name is ignored, and the status of each request is the one the
 * configuration says. A person can be made slow or failing to try the unavailable source. Never registered in
 * production (AuthenticSourceConfiguration).
 *
 * <p>{@code settings.people} of the tenant's source: {@code [{"identifiers": [{"type", "value"}],
 * "not_found", "requests": [{"source_ref", "status", "mismatched_fields", "renewal", "requested_by",
 * "requested_at", "display_identifier", "organization", "attributes": {...}}], "delay_seconds", "fail",
 * "dispute_supported", "dispute_fail"}]}. {@code dispute_supported: false} makes the person's source refuse the
 * notice of data not correct, and {@code dispute_fail: true} makes it fail.
 */
@Component
class SimulatedAuthenticSourceAdapter implements AuthenticSourceAdapter {

    static final String TYPE = "simulated";

    private final ObjectMapper json;

    SimulatedAuthenticSourceAdapter(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Set<IdentifierType> supportedIdentifierTypes() {
        return Set.of(IdentifierType.PERSONAL_ID, IdentifierType.COLEGIADO);
    }

    @Override
    public SourceRequests lookup(TenantId tenant, String attestationType, Holder holder, SourceSettings settings,
                                 Duration timeout) {
        return person(settings, holder).map(SimulatedAuthenticSourceAdapter::answer)
                .orElseGet(SourceRequests::notFound);
    }

    /** Whether it can be told depends on the person, so it is only known when it is told. */
    @Override
    public boolean supportsDispute(SourceSettings settings) {
        return true;
    }

    @Override
    public void dispute(TenantId tenant, String attestationType, Holder holder, String sourceRef,
                        SourceSettings settings, Duration timeout) {
        SimulatedPerson person = person(settings, holder).orElseThrow(
                () -> new IllegalStateException("Simulated person not found"));
        if (Boolean.FALSE.equals(person.disputeSupported())) {
            throw new DisputeNotSupportedException();
        }
        if (Boolean.TRUE.equals(person.disputeFail())) {
            throw new IllegalStateException("Simulated dispute failure");
        }
    }

    private Optional<SimulatedPerson> person(SourceSettings settings, Holder holder) {
        return people(settings).stream().filter(person -> person.hasIdentifier(holder.identifier())).findFirst();
    }

    private static SourceRequests answer(SimulatedPerson person) {
        if (person.delaySeconds() != null && person.delaySeconds() > 0) {
            try {
                Thread.sleep(Duration.ofSeconds(person.delaySeconds()));
            } catch (InterruptedException e) {
                // The module stopped waiting: give up quietly, nobody reads this answer.
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Simulated search interrupted", e);
            }
        }
        if (Boolean.TRUE.equals(person.fail())) {
            throw new IllegalStateException("Simulated source failure");
        }
        if (Boolean.TRUE.equals(person.notFound())) {
            return SourceRequests.notFound();
        }
        if (person.requests() == null || person.requests().isEmpty()) {
            return SourceRequests.noRequests();
        }
        return new SourceRequests.Requests(person.requests().stream().map(SimulatedRequest::toSourceRequest).toList());
    }

    private List<SimulatedPerson> people(SourceSettings settings) {
        Object people = settings.adapterSettings().get("people");
        if (people == null) {
            return List.of();
        }
        try {
            return List.of(json.convertValue(people, SimulatedPerson[].class));
        } catch (IllegalArgumentException _) {
            // Jackson's message may quote a person: name the setting only.
            throw new IllegalStateException("settings.people of the simulated source is invalid");
        }
    }

    /** One synthetic person. Its text form carries no data. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SimulatedPerson(@JsonProperty("identifiers") List<SimulatedIdentifier> identifiers,
                           @JsonProperty("not_found") Boolean notFound,
                           @JsonProperty("requests") List<SimulatedRequest> requests,
                           @JsonProperty("delay_seconds") Integer delaySeconds,
                           @JsonProperty("fail") Boolean fail,
                           @JsonProperty("dispute_supported") Boolean disputeSupported,
                           @JsonProperty("dispute_fail") Boolean disputeFail) {

        boolean hasIdentifier(PersonalIdentifier searched) {
            return identifiers != null && identifiers.stream().anyMatch(identifier -> identifier.matches(searched));
        }

        @Override
        public String toString() {
            return "SimulatedPerson[redacted]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SimulatedIdentifier(@JsonProperty("type") String type, @JsonProperty("value") String value) {

        boolean matches(PersonalIdentifier searched) {
            return IdentifierType.fromCode(type)
                    .filter(known -> value != null && !value.isBlank())
                    .map(known -> new PersonalIdentifier(known, value).sameAs(searched))
                    .orElse(false);
        }

        @Override
        public String toString() {
            return "SimulatedIdentifier[redacted]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SimulatedRequest(@JsonProperty("source_ref") String sourceRef,
                            @JsonProperty("status") String status,
                            @JsonProperty("mismatched_fields") List<String> mismatchedFields,
                            @JsonProperty("renewal") boolean renewal,
                            @JsonProperty("requested_by") String requestedBy,
                            @JsonProperty("requested_at") String requestedAt,
                            @JsonProperty("display_identifier") String displayIdentifier,
                            @JsonProperty("organization") String organization,
                            @JsonProperty("attributes") Map<String, Object> attributes) {

        SourceRequest toSourceRequest() {
            try {
                return new SourceRequest(sourceRef, RequestStatus.fromCode(status).orElseThrow(),
                        mismatchedFields == null ? List.of()
                                : mismatchedFields.stream().map(code -> MismatchedField.fromCode(code).orElseThrow())
                                .toList(),
                        renewal, requestedBy, requestedAt == null ? null : Instant.parse(requestedAt),
                        displayIdentifier, organization, attributes);
            } catch (RuntimeException _) {
                // The cause's message may quote the request: name the setting only.
                throw new IllegalStateException("settings.people of the simulated source is invalid");
            }
        }

        @Override
        public String toString() {
            return "SimulatedRequest[redacted]";
        }
    }
}
