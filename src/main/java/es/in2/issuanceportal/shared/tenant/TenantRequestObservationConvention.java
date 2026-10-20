package es.in2.issuanceportal.shared.tenant;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

/**
 * Adds {@code tenant.id} to {@code http.server.requests} and to the server span, so error rate and
 * latency can be read per tenant (conv-observability §3, §4). Requests outside the API have none.
 */
@Component
public class TenantRequestObservationConvention extends DefaultServerRequestObservationConvention {

    static final String TENANT_KEY = "tenant.id";
    private static final String NONE = "none";

    @Override
    public KeyValues getLowCardinalityKeyValues(ServerRequestObservationContext context) {
        Object tenant = context.getCarrier().getAttribute(TenantFilter.ATTRIBUTE);
        String value = tenant instanceof TenantId id ? id.value() : NONE;
        return super.getLowCardinalityKeyValues(context).and(KeyValue.of(TENANT_KEY, value));
    }
}
