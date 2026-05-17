// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

/**
 * Tests for the {@link SignatureVerifier} interface contract.
 *
 * <p>DEC-22 Q-1a TDD RED-first (AC1). These tests verify the behavioral contract of the interface
 * and its expected composability — any implementation must satisfy them.
 *
 * @see <a href="../../../../../../../../docs/governance/stories/E38S04.story.md">E38S04 AC1,
 *     AC6</a>
 */
class SignatureVerifierTest {

    @Test
    void verify_validSignature_returnsTrue() {
        // Contract test: a mock implementation satisfying the interface contract
        SignatureVerifier verifier = mock(SignatureVerifier.class);
        byte[] payload = "hello".getBytes();
        byte[] signature = new byte[64];
        byte[] publicKey = new byte[32];
        when(verifier.verify(any(), any(), any())).thenReturn(true);

        assertThat(verifier.verify(payload, signature, publicKey)).isTrue();
    }

    @Test
    void verify_invalidSignature_returnsFalse() {
        SignatureVerifier verifier = mock(SignatureVerifier.class);
        byte[] payload = "hello".getBytes();
        byte[] signature = new byte[64]; // wrong signature
        byte[] publicKey = new byte[32];
        when(verifier.verify(any(), any(), any())).thenReturn(false);

        assertThat(verifier.verify(payload, signature, publicKey)).isFalse();
    }

    @Test
    void signatureVerifier_hasCorrectMethodSignature() throws NoSuchMethodException {
        // AC6: interface signature is boolean verify(byte[] payload, byte[] signature, byte[]
        // publicKey)
        var method =
                SignatureVerifier.class.getMethod(
                        "verify", byte[].class, byte[].class, byte[].class);
        assertThat(method.getReturnType()).isEqualTo(boolean.class);
        assertThat(method.getParameterCount()).isEqualTo(3);
    }
}
