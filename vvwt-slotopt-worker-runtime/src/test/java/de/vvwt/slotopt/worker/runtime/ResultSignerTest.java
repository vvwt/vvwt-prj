package de.vvwt.slotopt.worker.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Interface-contract tests for {@link ResultSigner}.
 *
 * <p>Moved from {@code vvwt-slotopt-standalone-worker} to {@code vvwt-slotopt-worker-runtime} in
 * E63S01 (AC-MOD-RUNTIME-LIBRARY-MODULE).
 *
 * <p>Story: E41S03 AC-RESULT-SIGNER-INTERFACE; E63S01 AC-MOD-RUNTIME-LIBRARY-MODULE.
 */
class ResultSignerTest {

    @Test
    void contractViaInterfaceType() {
        ResultSigner signer = mock(ResultSigner.class);
        SubmitResultPayload payload =
                new SubmitResultPayload(
                        UUID.randomUUID(), UUID.randomUUID(), "Ed25519", "{\"result\":1}");

        byte[] fakeSig = new byte[64];
        when(signer.signResult(any())).thenReturn(fakeSig);

        byte[] result = signer.signResult(payload);

        assertThat(result).hasSize(64);
    }

    @Test
    void signResultDeclaredToThrowSigningException() {
        ResultSigner signer =
                (payload) -> {
                    throw new SigningException("test", null);
                };
        SubmitResultPayload payload =
                new SubmitResultPayload(
                        UUID.randomUUID(), UUID.randomUUID(), "Ed25519", "{\"r\":0}");

        assertThatThrownBy(() -> signer.signResult(payload))
                .isInstanceOf(SigningException.class)
                .hasMessage("test");
    }
}
