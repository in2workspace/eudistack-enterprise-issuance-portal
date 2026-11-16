package es.in2.issuanceportal.authenticsource;

import es.in2.issuanceportal.authenticsource.application.port.AuthenticSourceAdapter;
import es.in2.issuanceportal.authenticsource.domain.Holder;
import es.in2.issuanceportal.authenticsource.domain.IdentifierType;
import es.in2.issuanceportal.authenticsource.domain.PersonalIdentifier;
import es.in2.issuanceportal.authenticsource.domain.SourceRequests;
import es.in2.issuanceportal.shared.tenant.TenantId;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * An adapter for the tests: answers what the test tells it and records every search, so a test can
 * check which holder and identifier reached the source.
 */
public class StubAdapter implements AuthenticSourceAdapter {

    private final String type;
    private final Set<IdentifierType> supported;
    private final List<Holder> holders = new ArrayList<>();
    private volatile Function<Holder, SourceRequests> answer = holder -> SourceRequests.notFound();

    public StubAdapter(String type, Set<IdentifierType> supported) {
        this.type = type;
        this.supported = supported;
    }

    public static StubAdapter searchingByBoth(String type) {
        return new StubAdapter(type, Set.of(IdentifierType.PERSONAL_ID, IdentifierType.COLEGIADO));
    }

    public StubAdapter answering(Function<Holder, SourceRequests> answer) {
        this.answer = answer;
        return this;
    }

    /** The identifier of every search, in order. */
    public synchronized List<PersonalIdentifier> searches() {
        return holders.stream().map(Holder::identifier).toList();
    }

    /** The whole identity of every search, in order. */
    public synchronized List<Holder> holders() {
        return List.copyOf(holders);
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
    public SourceRequests lookup(TenantId tenant, String attestationType, Holder holder, Duration timeout) {
        synchronized (this) {
            holders.add(holder);
        }
        return answer.apply(holder);
    }
}
