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
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MachineCredentialSourceTest {

    private static final TenantId SANDBOX = new TenantId("sandbox");
    private static final String TEMPLATE = "/eudistack/dev/{tenant}/machine-credential";

    @TempDir
    Path directory;

    @Test
    @DisplayName("Parser — a secret with a credential and a JWK private key is read")
    void parse_validSecret_returnsIdentity() {
        MachineCredentialFixture fixture = MachineCredentialFixture.create("sandbox");

        MachineIdentity identity = MachineCredentialParser.parse(fixture.secretJson()).orElseThrow();

        assertThat(identity.credential().clientId()).isEqualTo(fixture.clientId());
        assertThat(identity.credential().tenant()).isEqualTo("sandbox");
        assertThat(identity.credential().compactJwt()).isEqualTo(fixture.credentialJwt());
        assertThat(identity.privateKey().isPrivate()).isTrue();
    }

    @Test
    @DisplayName("Parser — the private key may be a hex scalar")
    void parse_hexPrivateKey_returnsIdentity() {
        MachineCredentialFixture fixture = MachineCredentialFixture.create("sandbox");
        String hex = new BigInteger(1, fixture.key().getD().decode()).toString(16);
        String secret = "{\"credential\":\"" + fixture.credentialJwt() + "\",\"privateKey\":\"0x" + hex + "\"}";

        MachineIdentity identity = MachineCredentialParser.parse(secret).orElseThrow();

        assertThat(identity.privateKey().toPublicJWK()).isEqualTo(fixture.key().toPublicJWK());
    }

    @Test
    @DisplayName("Parser — a clientId in the secret is accepted and ignored")
    void parse_secretWithClientId_clientIdComesFromCredential() {
        MachineCredentialFixture fixture = MachineCredentialFixture.create("sandbox");
        String secret = "{\"clientId\":\"did:key:other\",\"credential\":\"" + fixture.credentialJwt()
                + "\",\"privateKey\":" + fixture.key().toJSONString() + "}";

        assertThat(MachineCredentialParser.parse(secret).orElseThrow().credential().clientId())
                .isEqualTo(fixture.clientId());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "not json",
            "{}",
            "{\"credential\":\"jwt\"}",
            "{\"credential\":\"not-a-jwt\",\"privateKey\":\"abc123\"}",
            "{\"credential\":\" \",\"privateKey\":{}}",
            "{\"credential\":\"jwt\",\"privateKey\":5}"
    })
    @DisplayName("Parser — a secret that is not usable gives no credential")
    void parse_unusableSecret_returnsEmpty(String content) {
        assertThat(MachineCredentialParser.parse(content)).isEmpty();
    }

    @Test
    @DisplayName("Parser — a public key without the private part gives no credential")
    void parse_publicKeyOnly_returnsEmpty() {
        MachineCredentialFixture fixture = MachineCredentialFixture.create("sandbox");
        String secret = "{\"credential\":\"" + fixture.credentialJwt() + "\",\"privateKey\":"
                + fixture.key().toPublicJWK().toJSONString() + "}";

        assertThat(MachineCredentialParser.parse(secret)).isEmpty();
    }

    @Test
    @DisplayName("Parser — a key of another curve gives no credential")
    void parse_otherCurve_returnsEmpty() throws Exception {
        MachineCredentialFixture fixture = MachineCredentialFixture.create("sandbox");
        String secret = "{\"credential\":\"" + fixture.credentialJwt() + "\",\"privateKey\":"
                + new ECKeyGenerator(Curve.P_384).generate().toJSONString() + "}";

        assertThat(MachineCredentialParser.parse(secret)).isEmpty();
    }

    @Test
    @DisplayName("NFR-3 — the identity does not show in toString")
    void toString_machineIdentity_showsNothing() {
        MachineCredentialFixture fixture = MachineCredentialFixture.create("sandbox");

        assertThat(MachineCredentialParser.parse(fixture.secretJson()).orElseThrow().toString())
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
    @DisplayName("SSM — reads, decrypted, the parameter named from the template with the tenant")
    void find_parameterOfTenant_returnsIdentity() {
        MachineCredentialFixture fixture = MachineCredentialFixture.create("sandbox");
        SsmClient ssm = mock(SsmClient.class);
        when(ssm.getParameter(anyRequest())).thenReturn(GetParameterResponse.builder()
                .parameter(Parameter.builder().value(fixture.secretJson()).build()).build());

        assertThat(new SsmMachineCredentialSource(ssm, TEMPLATE).find(SANDBOX)).isPresent();

        GetParameterRequest.Builder request = GetParameterRequest.builder();
        ArgumentCaptor<Consumer<GetParameterRequest.Builder>> captor = ArgumentCaptor.captor();
        verify(ssm).getParameter(captor.capture());
        captor.getValue().accept(request);
        assertThat(request.build().name()).isEqualTo("/eudistack/dev/sandbox/machine-credential");
        assertThat(request.build().withDecryption()).isTrue();
    }

    @Test
    @DisplayName("AC-05 — SSM: a tenant without parameter has no credential")
    void find_parameterNotFound_returnsEmpty() {
        SsmClient ssm = mock(SsmClient.class);
        when(ssm.getParameter(anyRequest())).thenThrow(ParameterNotFoundException.builder().message("nope").build());

        assertThat(new SsmMachineCredentialSource(ssm, TEMPLATE).find(SANDBOX)).isEmpty();
    }

    @Test
    @DisplayName("SSM — a parameter that is not a usable secret has no credential")
    void find_parameterNotUsable_returnsEmpty() {
        SsmClient ssm = mock(SsmClient.class);
        when(ssm.getParameter(anyRequest())).thenReturn(GetParameterResponse.builder()
                .parameter(Parameter.builder().value("not json").build()).build());

        assertThat(new SsmMachineCredentialSource(ssm, TEMPLATE).find(SANDBOX)).isEmpty();
    }

    @Test
    @DisplayName("ES-05 — SSM: a failure of the store is reported without its message")
    void find_storeFails_storeUnavailable() {
        SsmClient ssm = mock(SsmClient.class);
        when(ssm.getParameter(anyRequest())).thenThrow(SdkClientException.create("connection reset"));

        assertThatThrownBy(() -> new SsmMachineCredentialSource(ssm, TEMPLATE).find(SANDBOX))
                .isInstanceOfSatisfying(SystemTokenUnavailableException.class, e -> {
                    assertThat(e.reason()).isEqualTo(Reason.SECRET_STORE_UNAVAILABLE);
                    assertThat(e.getCause()).isNull();
                });
    }

    private static Consumer<GetParameterRequest.Builder> anyRequest() {
        return any();
    }
}
