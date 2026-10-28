package es.in2.issuanceportal.authenticsource.application.port;

import es.in2.issuanceportal.authenticsource.domain.IdentifierType;
import es.in2.issuanceportal.authenticsource.domain.LookupResult;
import es.in2.issuanceportal.authenticsource.domain.PersonalIdentifier;
import es.in2.issuanceportal.shared.tenant.TenantId;

import java.time.Duration;
import java.util.Set;

/**
 * One kind of authentic source (design.md §3.1), adapted from {@code DataAcquisitionProvider} of
 * {@code eudistack-enterprise-backend}. An adapter only talks to its source and searches by the type it
 * receives; deciding on the answer belongs to the module, the same for every adapter.
 */
public interface AuthenticSourceAdapter {

    /** The name the tenant's configuration uses to choose this adapter, e.g. {@code simulated}. */
    String type();

    /** The identifier types this source can be searched by. */
    Set<IdentifierType> supportedIdentifierTypes();

    /**
     * Searches the source configured for the tenant and attestation type by {@code identifier}, of a type
     * it supports. Any failure of the source is thrown; the caller turns it into an unavailable source.
     */
    LookupResult lookup(TenantId tenant, String attestationType, PersonalIdentifier identifier, Duration timeout);
}
