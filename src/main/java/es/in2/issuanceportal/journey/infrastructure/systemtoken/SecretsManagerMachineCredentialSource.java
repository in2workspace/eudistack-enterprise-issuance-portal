package es.in2.issuanceportal.journey.infrastructure.systemtoken;

import es.in2.issuanceportal.journey.application.port.SystemTokenUnavailableException;
import es.in2.issuanceportal.shared.tenant.TenantId;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

import java.util.Optional;

/** The tenant's secret in AWS Secrets Manager, named from a template (design.md AD-5). */
final class SecretsManagerMachineCredentialSource implements MachineCredentialSource {

    private final SecretsManagerClient secrets;
    private final String secretNameTemplate;

    SecretsManagerMachineCredentialSource(SecretsManagerClient secrets, String secretNameTemplate) {
        this.secrets = secrets;
        this.secretNameTemplate = secretNameTemplate;
    }

    @Override
    public Optional<MachineCredential> find(TenantId tenant) {
        String name = secretNameTemplate.replace(SystemTokenProperties.TENANT_PLACEHOLDER, tenant.value());
        try {
            String content = secrets.getSecretValue(request -> request.secretId(name)).secretString();
            return content == null ? Optional.empty() : MachineCredentialParser.parse(content);
        } catch (ResourceNotFoundException e) {
            return Optional.empty();
        } catch (SdkException e) {
            throw new SystemTokenUnavailableException(SystemTokenUnavailableException.Reason.SECRET_STORE_UNAVAILABLE);
        }
    }
}
