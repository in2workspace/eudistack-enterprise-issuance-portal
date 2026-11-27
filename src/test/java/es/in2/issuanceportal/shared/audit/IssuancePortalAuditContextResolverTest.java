package es.in2.issuanceportal.shared.audit;

import com.eudistack.auditoria.autoconfigure.AuditoriaProperties;
import com.eudistack.auditoria.domain.ActorKind;
import com.eudistack.auditoria.domain.AuditActor;
import com.eudistack.auditoria.domain.AuditContext;
import es.in2.issuanceportal.session.api.HolderRef;
import es.in2.issuanceportal.shared.security.HolderPrincipal;
import es.in2.issuanceportal.shared.security.HolderRefs;
import es.in2.issuanceportal.shared.tenant.TenantId;
import es.in2.issuanceportal.shared.tenant.TestTenants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IssuancePortalAuditContextResolverTest {

    private static final String SOURCE = "issuance-portal";
    private static final HolderRef HOLDER_REF = HolderRef.of("a".repeat(64));
    private static final HolderPrincipal HOLDER = new HolderPrincipal("https://sandbox.example.org/verifier",
            "subject-1234", "Ada", "Lovelace", "12345678Z", null, List.of("hwk"), null);

    private final HolderRefs holders = mock(HolderRefs.class);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private IssuancePortalAuditContextResolver resolver;

    @BeforeEach
    void setUp() {
        AuditoriaProperties properties = new AuditoriaProperties();
        properties.setSource(SOURCE);
        resolver = new IssuancePortalAuditContextResolver(holders, properties);
        when(holders.of(HOLDER)).thenReturn(HOLDER_REF);
    }

    @Nested
    @DisplayName("AC-05 — a request of a holder")
    class HolderRequest {

        @Test
        void resolve_holderRequest_returnsTenantAndHolderReference() {
            // Arrange
            TestTenants.resolved(request, new TenantId("cgcom"));
            Authentication authentication = new TestingAuthenticationToken(HOLDER, null);

            // Act
            Optional<AuditContext> context = resolver.resolve(request, authentication);

            // Assert
            assertThat(context).hasValueSatisfying(resolved -> {
                assertThat(resolved.tenant()).isEqualTo("cgcom");
                assertThat(resolved.actor().kind()).isEqualTo(ActorKind.HOLDER);
                assertThat(resolved.actor().id()).isEqualTo(HOLDER_REF.value());
                assertThat(resolved.organization()).isNull();
                assertThat(resolved.correlationId()).isNull();
            });
        }

        @Test
        void resolve_holderWithIdentityClaims_carriesNoPersonalData() {
            // Arrange
            TestTenants.resolved(request, new TenantId("sandbox"));
            Authentication authentication = new TestingAuthenticationToken(HOLDER, null);

            // Act
            AuditContext context = resolver.resolve(request, authentication).orElseThrow();

            // Assert
            assertThat(context.actor()).isEqualTo(AuditActor.holder(HOLDER_REF.value()));
            assertThat(context.toString()).doesNotContain(HOLDER.subject(), HOLDER.givenName(),
                    HOLDER.familyName(), HOLDER.personalAdministrativeNumber());
        }
    }

    @Nested
    @DisplayName("EC-05 — a request without a holder")
    class WithoutHolder {

        @Test
        void resolve_noAuthentication_returnsSystemActor() {
            // Arrange
            TestTenants.resolved(request, new TenantId("sandbox"));

            // Act
            AuditContext context = resolver.resolve(request, null).orElseThrow();

            // Assert
            assertThat(context.actor()).isEqualTo(AuditActor.system(SOURCE));
            verifyNoInteractions(holders);
        }

        @Test
        void resolve_anonymousAuthentication_returnsSystemActor() {
            // Arrange
            TestTenants.resolved(request, new TenantId("sandbox"));
            Authentication anonymous = new AnonymousAuthenticationToken("key", "anonymousUser",
                    AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

            // Act
            AuditContext context = resolver.resolve(request, anonymous).orElseThrow();

            // Assert
            assertThat(context.actor()).isEqualTo(AuditActor.system(SOURCE));
            verifyNoInteractions(holders);
        }

        @Test
        void resolve_requestWithoutTenant_returnsEmpty() {
            // Arrange
            Authentication authentication = new TestingAuthenticationToken(HOLDER, null);

            // Act
            Optional<AuditContext> context = resolver.resolve(request, authentication);

            // Assert
            assertThat(context).isEmpty();
        }
    }
}
