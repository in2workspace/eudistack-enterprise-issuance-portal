package es.in2.issuanceportal.authenticsource;

import es.in2.issuanceportal.authenticsource.application.port.AuthenticSourceAdapter;
import es.in2.issuanceportal.authenticsource.domain.IdentifierType;
import es.in2.issuanceportal.authenticsource.domain.LookupResult;
import es.in2.issuanceportal.authenticsource.domain.PersonalIdentifier;
import es.in2.issuanceportal.shared.tenant.TenantId;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * An adapter for the tests: answers what the test tells it and records every search, so a test can
 * check which identifier reached the source.
 */
public class StubAdapter implements AuthenticSourceAdapter {

    private final String type;
    private final Set<IdentifierType> supported;
    private final List<PersonalIdentifier> searches = new ArrayList<>();
    private volatile Function<PersonalIdentifier, LookupResult> answer = identifier -> LookupResult.notFound();

    public StubAdapter(String type, Set<IdentifierType> supported) {
        this.type = type;
        this.supported = supported;
    }

    public static StubAdapter searchingByBoth(String type) {
        return new StubAdapter(type, Set.of(IdentifierType.DNI, IdentifierType.COLEGIADO));
    }

    public StubAdapter answering(Function<PersonalIdentifier, LookupResult> answer) {
        this.answer = answer;
        return this;
    }

    public synchronized List<PersonalIdentifier> searches() {
        return List.copyOf(searches);
    }

    @Override
    public String type() {
        return type;
    }

    @Override
    public Set<IdentifierType> supportedIdentifierTypes() {
        return supported;
    }

    @Override
    public LookupResult lookup(TenantId tenant, String attestationType, PersonalIdentifier identifier,
                               Duration timeout) {
        synchronized (this) {
            searches.add(identifier);
        }
        return answer.apply(identifier);
    }
}
