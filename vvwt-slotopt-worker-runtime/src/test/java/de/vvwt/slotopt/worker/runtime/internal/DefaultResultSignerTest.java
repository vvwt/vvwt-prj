// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.worker.runtime.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.vvwt.slotopt.worker.identity.KeyRotationResult;
import de.vvwt.slotopt.worker.identity.WorkerKeyManager;
import de.vvwt.slotopt.worker.runtime.ResultSigner;
import de.vvwt.slotopt.worker.runtime.SigningException;
import de.vvwt.slotopt.worker.runtime.SubmitResultPayload;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.SignatureException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Same-package white-box tests for {@link DefaultResultSigner}.
 *
 * <p>Moved from {@code vvwt-slotopt-standalone-worker} to {@code vvwt-slotopt-worker-runtime} in
 * E63S01 (the implementation moved into the shared library — AC-MOD-RUNTIME-LIBRARY-MODULE,
 * AC-SEC-SIGNING-BEHAVIOUR-PRESERVED).
 *
 * <p>DEC-36: same-package test in {@code de.vvwt.slotopt.worker.runtime.internal} — MAY reference
 * {@link DefaultResultSigner} directly. Cross-module WorkerKeyManager collaborator is referenced
 * via the public interface {@link WorkerKeyManager}.
 *
 * <p>Story: E41S03 AC-DEFAULT-RESULT-SIGNER (adapted for shared library); E63S01
 * AC-SEC-SIGNING-BEHAVIOUR-PRESERVED.
 */
class DefaultResultSignerTest {

    private static final String ED25519 = "Ed25519";

    private static SubmitResultPayload samplePayload() {
        return new SubmitResultPayload(
                UUID.randomUUID(), UUID.randomUUID(), ED25519, "{\"result\":42}");
    }

    // -----------------------------------------------------------------------
    // TC-4: valid algorithm at construction — no exception
    // -----------------------------------------------------------------------

    @Test
    void constructionSucceedsWithEd25519Algorithm() {
        WorkerKeyManager keyManager = mock(WorkerKeyManager.class);
        // must not throw
        DefaultResultSigner signer = new DefaultResultSigner(keyManager, ED25519);
        assertThat(signer).isNotNull();
    }

    // -----------------------------------------------------------------------
    // TC-5: invalid algorithm at construction → IllegalStateException
    // -----------------------------------------------------------------------

    @Test
    void constructionFailsForNonEd25519Algorithm() {
        WorkerKeyManager keyManager = mock(WorkerKeyManager.class);
        assertThatThrownBy(() -> new DefaultResultSigner(keyManager, "RSA"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V1 supports only Ed25519");
    }

    @Test
    void constructionFailsForMLDSA65Algorithm() {
        WorkerKeyManager keyManager = mock(WorkerKeyManager.class);
        assertThatThrownBy(() -> new DefaultResultSigner(keyManager, "ML-DSA-65"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V1 supports only Ed25519");
    }

    // -----------------------------------------------------------------------
    // TC-6: signResult delegates to WorkerKeyManager
    // -----------------------------------------------------------------------

    @Test
    void signResultDelegatesToWorkerKeyManager() {
        WorkerKeyManager keyManager = mock(WorkerKeyManager.class);
        byte[] fakeSig = new byte[64];
        when(keyManager.signResult(any())).thenReturn(fakeSig);

        DefaultResultSigner signer = new DefaultResultSigner(keyManager, ED25519);
        SubmitResultPayload payload = samplePayload();

        byte[] result = signer.signResult(payload);

        assertThat(result).isSameAs(fakeSig);
    }

    // -----------------------------------------------------------------------
    // TC-7: canonical bytes are UTF-8 of resultPayloadJson
    // -----------------------------------------------------------------------

    @Test
    void signResultPassesUtf8OfResultPayloadJsonToKeyManager() {
        WorkerKeyManager keyManager = mock(WorkerKeyManager.class);
        when(keyManager.signResult(any())).thenReturn(new byte[64]);

        DefaultResultSigner signer = new DefaultResultSigner(keyManager, ED25519);

        String json = "{\"result\":99,\"extra\":\"value\"}";
        SubmitResultPayload payload =
                new SubmitResultPayload(UUID.randomUUID(), UUID.randomUUID(), ED25519, json);

        signer.signResult(payload);

        byte[] expectedCanonicalBytes = json.getBytes(StandardCharsets.UTF_8);
        verify(keyManager).signResult(expectedCanonicalBytes);
    }

    // -----------------------------------------------------------------------
    // TC-8: signResult wraps IllegalStateException in SigningException
    // -----------------------------------------------------------------------

    @Test
    void signResultWrapsKeyManagerExceptionInSigningException() {
        WorkerKeyManager keyManager = mock(WorkerKeyManager.class);
        IllegalStateException jceError =
                new IllegalStateException("Failed to sign result bytes with Ed25519 key");
        when(keyManager.signResult(any())).thenThrow(jceError);

        DefaultResultSigner signer = new DefaultResultSigner(keyManager, ED25519);

        assertThatThrownBy(() -> signer.signResult(samplePayload()))
                .isInstanceOf(SigningException.class)
                .hasCause(jceError)
                .hasMessageContaining("sign");
    }

    // -----------------------------------------------------------------------
    // TC-9: signResult with null payload → NullPointerException
    // -----------------------------------------------------------------------

    @Test
    void signResultThrowsNpeForNullPayload() {
        WorkerKeyManager keyManager = mock(WorkerKeyManager.class);
        DefaultResultSigner signer = new DefaultResultSigner(keyManager, ED25519);

        assertThatThrownBy(() -> signer.signResult(null)).isInstanceOf(NullPointerException.class);
    }

    // -----------------------------------------------------------------------
    // TC-10: Round-trip — sign with inline JCA impl + verify
    // -----------------------------------------------------------------------

    @Test
    void signResultRoundTripWithInlineJcaKeyManager()
            throws NoSuchAlgorithmException, InvalidKeyException, SignatureException {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(ED25519);
        KeyPair keyPair = kpg.generateKeyPair();

        WorkerKeyManager realKeyManager =
                new WorkerKeyManager() {
                    @Override
                    public String algorithmId() {
                        return ED25519;
                    }

                    @Override
                    public boolean isNewRegistrationRequired() {
                        return false;
                    }

                    @Override
                    public byte[] signResult(byte[] canonicalResultBytes) {
                        try {
                            Signature sig = Signature.getInstance(ED25519);
                            sig.initSign(keyPair.getPrivate());
                            sig.update(canonicalResultBytes);
                            return sig.sign();
                        } catch (NoSuchAlgorithmException
                                | InvalidKeyException
                                | SignatureException e) {
                            throw new IllegalStateException(
                                    "Failed to sign result bytes with Ed25519 key", e);
                        }
                    }

                    @Override
                    public byte[] getPublicKeyBytes() {
                        byte[] encoded = keyPair.getPublic().getEncoded();
                        byte[] raw = new byte[32];
                        System.arraycopy(encoded, encoded.length - 32, raw, 0, 32);
                        return raw;
                    }

                    @Override
                    public KeyRotationResult rotateKeypair() {
                        throw new UnsupportedOperationException("not needed in test");
                    }
                };

        DefaultResultSigner signer = new DefaultResultSigner(realKeyManager, ED25519);

        String json = "{\"key\":\"value\",\"num\":1}";
        SubmitResultPayload payload =
                new SubmitResultPayload(UUID.randomUUID(), UUID.randomUUID(), ED25519, json);

        byte[] signature = signer.signResult(payload);

        Signature verifier = Signature.getInstance(ED25519);
        verifier.initVerify(keyPair.getPublic());
        verifier.update(json.getBytes(StandardCharsets.UTF_8));
        boolean valid = verifier.verify(signature);

        assertThat(valid).isTrue();
        assertThat(signature).hasSize(64);
    }

    // -----------------------------------------------------------------------
    // Interface instance check
    // -----------------------------------------------------------------------

    @Test
    void implementsResultSignerInterface() {
        WorkerKeyManager keyManager = mock(WorkerKeyManager.class);
        DefaultResultSigner signer = new DefaultResultSigner(keyManager, ED25519);
        assertThat(signer).isInstanceOf(ResultSigner.class);
    }
}
