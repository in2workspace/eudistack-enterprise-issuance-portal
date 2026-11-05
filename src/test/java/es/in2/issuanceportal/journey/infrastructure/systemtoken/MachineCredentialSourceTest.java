package es.in2.issuanceportal.journey.infrastructure.systemtoken;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import es.in2.issuanceportal.journey.application.port.SystemTokenUnavailableException;
import es.in2.issuanceportal.journey.application.port.SystemTokenUnavailableException.Reason;
import es.in2.issuanceportal.shared.tenant.TenantId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MachineCredentialSourceTest {

    private static final TenantId SANDBOX = new TenantId("sandbox");

    @TempDir
    Path directory;

    @Test
    @DisplayName("Parser — a secret with clientId, credential and a private P-256 key is read")
    void parse_validSecret_returnsCredential() {
        MachineCredentialFixture fixture = MachineCredentialFixture.create("sandbox");

        MachineCredential credential = MachineCredentialParser.parse(fixture.secretJson()).orElseThrow();

        assertThat(credential.clientId()).isEqualTo(fixture.clientId());
        assertThat(credential.credentialJwt()).isEqualTo(fixture.credentialJwt());
        assertThat(credential.privateKey().isPrivate()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "not json",
            "{}",
            "{\"clientId\":\"did:key:z\",\"credential\":\"jwt\"}",
            "{\"clientId\":\"did:key:z\",\"credential\":\"jwt\",\"privateKey\":\"not an object\"}",
            "{\"clientId\":\" \",\"credential\":\"jwt\",\"privateKey\":{}}"
    })
    @DisplayName("Parser — a secret that is not usable gives no credential")
    void parse_unusableSecret_returnsEmpty(String content) {
        assertThat(MachineCredentialParser.parse(content)).isEmpty();
    }

    @Test
    @DisplayName("Parser — a public key without the private part gives no credential")
    void parse_publicKeyOnly_returnsEmpty() {
        MachineCredentialFixture fixture = MachineCredentialFixture.create("sandbox");
        String secret = "{\"clientId\":\"c\",\"credential\":\"jwt\",\"privateKey\":"
                + fixture.key().toPublicJWK().toJSONString() + "}";

        assertThat(MachineCredentialParser.parse(secret)).isEmpty();
    }

    @Test
    @DisplayName("Parser — a key of another curve gives no credential")
    void parse_otherCurve_returnsEmpty() throws Exception {
        String secret = "{\"clientId\":\"c\",\"credential\":\"jwt\",\"privateKey\":"
                + new ECKeyGenerator(Curve.P_384).generate().toJSONString() + "}";

        assertThat(MachineCredentialParser.parse(secret)).isEmpty();
    }

    @Test
    @DisplayName("NFR-3 — the credential does not show in toString")
    void toString_machineCredential_showsNothing() {
        MachineCredentialFixture fixture = MachineCredentialFixture.create("sandbox");

        assertThat(fixture.credential().toString())
                .doesNotContain(fixture.credentialJwt())
                .doesNotContain(fixture.clientId());
    }

    @Test
    @DisplayName("File source — reads the tenant's file")
    void find_fileOfTenant_returnsCredential() throws IOException {
        MachineCredentialFixture.create("sandbox").writeTo(directory, "sandbox");

        assertThat(new FileMachineCredentialSource(directory).find(SANDBOX)).isPresent();
    }

    @Test
    @DisplayName("AC-05 — File source: a tenant without file has no credential")
    void find_noFileForTenant_returnsEmpty() throws IOException {
        MachineCredentialFixture.create("kpmg").writeTo(directory, "kpmg");

        assertThat(new FileMachineCredentialSource(directory).find(SANDBOX)).isEmpty();
    }

    @Test
    @DisplayName("File source — an unreadable file means the store is unavailable")
    void find_unreadableFile_storeUnavailable() throws IOException {
        Files.write(directory.resolve("sandbox.json"), new byte[] {(byte) 0xC3, 0x28});

        assertThatThrownBy(() -> new FileMachineCredentialSource(directory).find(SANDBOX))
                .isInstanceOfSatisfying(SystemTokenUnavailableException.class,
                        e -> assertThat(e.reason()).isEqualTo(Reason.SECRET_STORE_UNAVAILABLE));
    }

    @Test
    @DisplayName("Secrets Manager — reads the secret named from the template with the tenant")
    void find_secretOfTenant_returnsCredential() {
        MachineCredentialFixture fixture = MachineCredentialFixture.create("sandbox");
        SecretsManagerClient secrets = mock(SecretsManagerClient.class);
        when(secrets.getSecretValue(anyRequest()))
                .thenAnswer(invocation -> {
                    GetSecretValueRequest.Builder request = GetSecretValueRequest.builder();
                    invocation.<Consumer<GetSecretValueRequest.Builder>>getArgument(0).accept(request);
                    return "eudistack/sandbox/portal".equals(request.build().secretId())
                            ? GetSecretValueResponse.builder().secretString(fixture.secretJson()).build()
                            : GetSecretValueResponse.builder().build();
                });

        assertThat(new SecretsManagerMachineCredentialSource(secrets, "eudistack/{tenant}/portal").find(SANDBOX))
                .isPresent();
    }

    @Test
    @DisplayName("AC-05 — Secrets Manager: a tenant without secret has no credential")
    void find_secretNotFound_returnsEmpty() {
        SecretsManagerClient secrets = mock(SecretsManagerClient.class);
        when(secrets.getSecretValue(anyRequest()))
                .thenThrow(ResourceNotFoundException.builder().message("not found").build());

        assertThat(new SecretsManagerMachineCredentialSource(secrets, "eudistack/{tenant}/portal").find(SANDBOX))
                .isEmpty();
    }

    @Test
    @DisplayName("ES-05 — Secrets Manager: a failure of the store is reported without its message")
    void find_storeFails_storeUnavailable() {
        SecretsManagerClient secrets = mock(SecretsManagerClient.class);
        when(secrets.getSecretValue(anyRequest()))
                .thenThrow(SdkClientException.create("connection reset"));

        assertThatThrownBy(() -> new SecretsManagerMachineCredentialSource(secrets, "eudistack/{tenant}/portal")
                .find(SANDBOX))
                .isInstanceOfSatisfying(SystemTokenUnavailableException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.SECRET_STORE_UNAVAILABLE);
                    assertThat(e.getCause()).isNull();
                });
    }

    private static Consumer<GetSecretValueRequest.Builder> anyRequest() {
        return any();
    }
}
