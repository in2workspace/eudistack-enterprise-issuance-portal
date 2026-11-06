package es.in2.issuanceportal.journey.infrastructure.systemtoken;

import es.in2.issuanceportal.journey.application.port.SystemTokenUnavailableException;
import es.in2.issuanceportal.shared.tenant.TenantId;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;

import java.util.Optional;

/** The tenant's SecureString parameter in SSM Parameter Store, named from a template (design.md AD-5). */
final class SsmMachineCredentialSource implements MachineCredentialSource {

    private final SsmClient ssm;
    private final String parameterTemplate;

    SsmMachineCredentialSource(SsmClient ssm, String parameterTemplate) {
        this.ssm = ssm;
        this.parameterTemplate = parameterTemplate;
    }

    @Override
    public Optional<MachineIdentity> find(TenantId tenant) {
        String name = parameterTemplate.replace(SystemTokenProperties.TENANT_PLACEHOLDER, tenant.value());
        try {
            String content = ssm.getParameter(request -> request.name(name).withDecryption(true)).parameter().value();
            return content == null ? Optional.empty() : MachineCredentialParser.parse(content);
        } catch (ParameterNotFoundException e) {
            return Optional.empty();
        } catch (SdkException e) {
            throw new SystemTokenUnavailableException(SystemTokenUnavailableException.Reason.SECRET_STORE_UNAVAILABLE);
        }
    }
}
