package es.in2.issuanceportal.authenticsource.application.port;

import es.in2.issuanceportal.authenticsource.domain.Holder;
import es.in2.issuanceportal.authenticsource.domain.IdentifierType;
import es.in2.issuanceportal.authenticsource.domain.SourceRequests;
import es.in2.issuanceportal.shared.tenant.TenantId;

import java.time.Duration;
import java.util.Set;

/**
 * One kind of authentic source (design.md §3.1), adapted from {@code DataAcquisitionProvider} of
 * {@code eudistack-enterprise-backend}. An adapter only talks to its source and translates its answer;
 * deciding on it belongs to the module, the same for every adapter.
 */
public interface AuthenticSourceAdapter {

    /** The name the tenant's configuration uses to choose this adapter, e.g. {@code simulated}. */
    String type();

    /** The identifier types this source can be searched by. */
    Set<IdentifierType> supportedIdentifierTypes();

    /**
     * Searches the source configured for the tenant and attestation type for the holder's requests, by the
     * identifier of a type it supports. Any failure of the source is thrown; the caller turns it into an
     * unavailable source. The status of each request is the source's: the module never compares identities.
     */
    SourceRequests lookup(TenantId tenant, String attestationType, Holder holder, Duration timeout);
}
