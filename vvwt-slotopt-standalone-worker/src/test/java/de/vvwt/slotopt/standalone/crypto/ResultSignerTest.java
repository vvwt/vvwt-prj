package de.vvwt.slotopt.standalone.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Interface-contract tests for {@link ResultSigner}.
 *
 * <p>TDD Iron Law (DEC-22): authored RED-first before {@link ResultSigner} exists.
 *
 * <p>DEC-36 cross-package: test is in {@code de.vvwt.slotopt.standalone.crypto} (same package as
 * the interface — DEC-35-by-analogy: interface lives in public root package). Uses the public
 * interface type, not the implementation.
 *
 * <p>Story: E41S03 AC-RESULT-SIGNER-INTERFACE.
 */
class ResultSignerTest {

    @Test
    void contractViaIntefaceType() {
        // A mock of the interface must be creatable — verifies interface shape
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
        // The method signature allows SigningException — verify compile-time declaration
        ResultSigner signer =
                (payload) -> {
                    throw new SigningException("test", null);
                };
        SubmitResultPayload payload =
                new SubmitResultPayload(
                        UUID.randomUUID(), UUID.randomUUID(), "Ed25519", "{\"r\":0}");

        org.assertj.core.api.ThrowableAssert.ThrowingCallable call =
                () -> signer.signResult(payload);

        org.assertj.core.api.Assertions.assertThatThrownBy(call)
                .isInstanceOf(SigningException.class)
                .hasMessage("test");
    }
}
