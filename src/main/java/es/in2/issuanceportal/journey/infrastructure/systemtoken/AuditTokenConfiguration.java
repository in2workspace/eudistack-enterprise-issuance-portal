package es.in2.issuanceportal.journey.infrastructure.systemtoken;

import com.eudistack.auditoria.autoconfigure.AuditoriaProperties;
import com.eudistack.auditoria.port.AuditAccessTokenProvider;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.time.Clock;
import java.util.Set;

/**
 * The portal's token for the Audit API, only with the audit dispatcher on. It is its own attestation, with the
 * power to write audit events, and not the tenant's one used towards the Issuer. A missing or unusable setting
 * stops the start, naming the variable that gives it.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("auditoria.dispatcher.enabled")
class AuditTokenConfiguration {

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    @Bean
    AuditAccessTokenProvider auditAccessTokenProvider(AuditTokenProperties properties, AuditoriaProperties auditoria,
                                                      Clock clock) {
        return providerFor(properties, auditoria.getClient().isAllowInsecureHttp(), clock);
    }

    static AuditAccessTokenProvider providerFor(AuditTokenProperties properties, boolean allowInsecureHttp,
                                                Clock clock) {
        String tokenUrl = required(properties.tokenUrl(), "AUDIT_M2M_TOKEN_URL");
        requireSecureTransport(tokenUrl, allowInsecureHttp);
        TenantId tenant = TenantId.parse(required(properties.tenant(), "AUDIT_M2M_TENANT"))
                .orElseThrow(() -> new IllegalStateException("AUDIT_M2M_TENANT is not a valid tenant identifier"));
        String audience = required(properties.assertionAudience(), "AUDIT_M2M_ASSERTION_AUDIENCE");
        MachineIdentity identity = identity(properties);

        ClientAssertionFactory assertions = new ClientAssertionFactory();
        VerifierTokenEndpoint endpoint = new VerifierTokenEndpoint(
                SystemTokenConfiguration.restClient(properties.requestTimeout()), clock);
        TokenGrant grant = requested -> endpoint.request(requested, tokenUrl, identity.credential().clientId(),
                assertions.build(identity, audience), headers -> { });
        return new VerifierAuditAccessTokenProvider(new CachingSystemTokenProvider(grant, clock), tenant);
    }

    private static MachineIdentity identity(AuditTokenProperties properties) {
        String clientId = required(properties.clientId(), "AUDIT_M2M_CLIENT_ID");
        String credential = required(properties.credential(), "AUDIT_M2M_CREDENTIAL");
        String privateKey = required(properties.privateKey(), "AUDIT_M2M_PRIVATE_KEY");
        MachineIdentity identity = MachineCredentialParser.parse(credential, privateKey)
                .orElseThrow(() -> new IllegalStateException(
                        "AUDIT_M2M_CREDENTIAL or AUDIT_M2M_PRIVATE_KEY cannot be used"));
        if (!clientId.equals(identity.credential().clientId())) {
            throw new IllegalStateException("AUDIT_M2M_CLIENT_ID is not the client of AUDIT_M2M_CREDENTIAL");
        }
        return identity;
    }

    /** The assertion and the token travel through this endpoint. */
    private static void requireSecureTransport(String tokenUrl, boolean allowInsecureHttp) {
        URI uri = URI.create(tokenUrl);
        if (uri.getHost() == null) {
            throw new IllegalStateException("AUDIT_M2M_TOKEN_URL must be an absolute URL");
        }
        boolean secure = "https".equalsIgnoreCase(uri.getScheme())
                || ("http".equalsIgnoreCase(uri.getScheme())
                && (allowInsecureHttp || LOOPBACK_HOSTS.contains(uri.getHost())));
        if (!secure) {
            throw new IllegalStateException("AUDIT_M2M_TOKEN_URL must be https: plain http is only accepted on "
                    + "localhost, or with auditoria.client.allow-insecure-http=true for a local stack");
        }
    }

    private static String required(String value, String variable) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(variable + " is not set");
        }
        return value.trim();
    }
}
