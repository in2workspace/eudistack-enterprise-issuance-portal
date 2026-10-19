package es.in2.issuanceportal.session.infrastructure.crypto;

import es.in2.issuanceportal.MutableClock;
import es.in2.issuanceportal.shared.error.ProblemCode;
import es.in2.issuanceportal.shared.error.ProblemException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DecryptResponse;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;
import software.amazon.awssdk.services.kms.model.KmsException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class KmsDataKeyProviderTest {

    private static final Duration ROTATION = Duration.ofHours(24);
    private static final byte[] CONTEXT = "journey-1/email".getBytes(StandardCharsets.UTF_8);

    private final KmsClient kms = mock(KmsClient.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-19T10:00:00Z"));
    private final KmsDataKeyProvider provider = new KmsDataKeyProvider(kms, "alias/issuance-portal", ROTATION, clock);
    private int generated;

    KmsDataKeyProviderTest() {
        when(kms.generateDataKey(any(Consumer.class))).thenAnswer(invocation -> {
            generated++;
            byte[] key = new byte[32];
            key[0] = (byte) generated;
            return GenerateDataKeyResponse.builder()
                    .plaintext(SdkBytes.fromByteArray(key))
                    .ciphertextBlob(SdkBytes.fromUtf8String("wrapped-" + generated))
                    .build();
        });
    }

    @Test
    void reusesTheDataKeyUntilTheRotationPeriodEnds() {
        DataKey first = provider.currentKey();
        clock.advance(ROTATION.minusSeconds(1));

        assertThat(provider.currentKey()).isSameAs(first);
        verify(kms, times(1)).generateDataKey(any(Consumer.class));
    }

    @Test
    void generatesANewDataKeyAfterTheRotationPeriod() {
        DataKey first = provider.currentKey();
        clock.advance(ROTATION);

        assertThat(provider.currentKey().wrapped()).isNotEqualTo(first.wrapped());
    }

    @Test
    void aValueWrittenByThisInstanceIsDecryptedWithoutCallingKms() {
        EnvelopeCipher cipher = new EnvelopeCipher(provider);

        byte[] sealed = cipher.encrypt("ana@example.org".getBytes(StandardCharsets.UTF_8), CONTEXT);

        assertThat(cipher.decrypt(sealed, CONTEXT)).asString(StandardCharsets.UTF_8).isEqualTo("ana@example.org");
        verify(kms, never()).decrypt(any(Consumer.class));
    }

    @Test
    void aValueWrittenByAnotherInstanceIsUnwrappedThroughKmsOnce() {
        byte[] sealed = new EnvelopeCipher(provider).encrypt("ana@example.org".getBytes(StandardCharsets.UTF_8), CONTEXT);
        KmsDataKeyProvider otherInstance = new KmsDataKeyProvider(kms, "alias/issuance-portal", ROTATION, clock);
        byte[] key = new byte[32];
        key[0] = 1;
        when(kms.decrypt(any(Consumer.class)))
                .thenReturn(DecryptResponse.builder().plaintext(SdkBytes.fromByteArray(key)).build());
        EnvelopeCipher cipher = new EnvelopeCipher(otherInstance);

        cipher.decrypt(sealed, CONTEXT);
        assertThat(cipher.decrypt(sealed, CONTEXT)).asString(StandardCharsets.UTF_8).isEqualTo("ana@example.org");

        verify(kms, times(1)).decrypt(any(Consumer.class));
    }

    @Test
    void aKmsFailureIsServiceUnavailable() {
        when(kms.generateDataKey(any(Consumer.class))).thenThrow(KmsException.builder().message("throttled").build());

        assertThatThrownBy(provider::currentKey)
                .isInstanceOf(ProblemException.class)
                .extracting(e -> ((ProblemException) e).problem())
                .isEqualTo(ProblemCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void aKeyIdIsRequired() {
        assertThatThrownBy(() -> new KmsDataKeyProvider(kms, " ", ROTATION, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
