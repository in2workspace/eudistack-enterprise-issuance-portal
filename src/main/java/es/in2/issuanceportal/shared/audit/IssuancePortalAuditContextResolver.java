package es.in2.issuanceportal.shared.audit;

import com.eudistack.auditoria.autoconfigure.AuditoriaProperties;
import com.eudistack.auditoria.domain.AuditActor;
import com.eudistack.auditoria.domain.AuditContext;
import com.eudistack.auditoria.port.AuditContextResolver;
import es.in2.issuanceportal.shared.security.HolderPrincipal;
import es.in2.issuanceportal.shared.security.HolderRefs;
import es.in2.issuanceportal.shared.tenant.CurrentTenant;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Tells the audit starter who is acting in a request: the tenant the tenant filter resolved and the holder of the
 * validated Access Token, or the portal itself in a call without one. A request without a tenant has no context.
 *
 * <p>The holder is named by their holder reference only: no name, email or subject leaves the journey's session.
 */
@Component
@ConditionalOnBooleanProperty("auditoria.publisher.enabled")
@RequiredArgsConstructor
class IssuancePortalAuditContextResolver implements AuditContextResolver {

    private final HolderRefs holders;
    private final AuditoriaProperties properties;

    @Override
    public Optional<AuditContext> resolve(HttpServletRequest request, Authentication authentication) {
        return CurrentTenant.find(request)
                .map(tenant -> new AuditContext(tenant.value(), null, null, actorOf(authentication)));
    }

    private AuditActor actorOf(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof HolderPrincipal holder) {
            return AuditActor.holder(holders.of(holder).value());
        }
        return AuditActor.system(properties.getSource());
    }
}
