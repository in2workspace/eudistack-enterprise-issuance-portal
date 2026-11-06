package es.in2.issuanceportal.journey.infrastructure.systemtoken;

import es.in2.issuanceportal.journey.application.port.SystemTokenUnavailableException;
import es.in2.issuanceportal.shared.tenant.TenantId;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Machine credentials mounted as files, {@code <directory>/<tenant>.json}: for Docker Compose. */
final class FileMachineCredentialSource implements MachineCredentialSource {

    private final Path directory;

    FileMachineCredentialSource(Path directory) {
        this.directory = directory;
    }

    @Override
    public Optional<MachineIdentity> find(TenantId tenant) {
        // A TenantId only has [a-z0-9_-], so it cannot leave the directory.
        Path file = directory.resolve(tenant.value() + ".json");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return MachineCredentialParser.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new SystemTokenUnavailableException(SystemTokenUnavailableException.Reason.SECRET_STORE_UNAVAILABLE);
        }
    }
}
