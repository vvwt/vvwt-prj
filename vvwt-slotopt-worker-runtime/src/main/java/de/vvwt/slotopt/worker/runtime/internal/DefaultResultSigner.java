// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.worker.runtime.internal;

import de.vvwt.slotopt.worker.identity.WorkerKeyManager;
import de.vvwt.slotopt.worker.runtime.ResultSigner;
import de.vvwt.slotopt.worker.runtime.SigningException;
import de.vvwt.slotopt.worker.runtime.SubmitResultPayload;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Default implementation of {@link ResultSigner}.
 *
 * <p>Delegates all signing to the injected {@link WorkerKeyManager} instance (from {@code
 * vvwt-slotopt-worker-lib}). This class does NOT re-implement cryptographic operations — it is a
 * thin adapter per Brief C-22 and DEC-35-by-analogy.
 *
 * <h2>Canonical bytes</h2>
 *
 * <p>Per Brief D-10 + O-6 (ii): the dispatcher's {@code DefaultSubmitResultService}
 * JCS-canonicalizes {@code resultPayloadJson} (RFC 8785 key-sorted, no-whitespace UTF-8) before
 * signature verification. Therefore, the canonical bytes to sign are the UTF-8 encoding of {@link
 * SubmitResultPayload#resultPayloadJson()}. The caller (the compute loop) is responsible for
 * providing a JCS-canonical JSON string.
 *
 * <h2>V1 single-algorithm constraint (DEC-43 D4)</h2>
 *
 * <p>V1 supports only {@code Ed25519}. If {@code signingAlgorithm} is not {@code "Ed25519"} at
 * construction time, an {@link IllegalStateException} is thrown immediately (defensive fail-fast).
 *
 * <h2>AC-SEC-SIGNING-BEHAVIOUR-PRESERVED</h2>
 *
 * <p>The Ed25519 result-signing behaviour (DEC-6 / DEC-43) moves into the shared library with no
 * change to the signed canonical byte layout or the signature algorithm.
 *
 * <p>DEC-35-by-analogy: implementation lives in {@code runtime.internal}; cross-module consumers
 * reference {@link ResultSigner} (the public interface) exclusively.
 *
 * <p>Story: E41S03 AC-DEFAULT-RESULT-SIGNER (moved and adapted to shared library in E63S01); E63S01
 * AC-SEC-SIGNING-BEHAVIOUR-PRESERVED.
 */
public final class DefaultResultSigner implements ResultSigner {

    /** V1 single-algorithm constraint per DEC-43 D4. */
    private static final String SUPPORTED_ALGORITHM = "Ed25519";

    private final WorkerKeyManager keyManager;

    /**
     * Constructs a {@code DefaultResultSigner}.
     *
     * @param keyManager the worker-lib {@link WorkerKeyManager} instance to delegate signing to;
     *     must not be {@code null}
     * @param signingAlgorithm the algorithm identifier; must be {@code "Ed25519"} in V1 (DEC-43 D4)
     * @throws IllegalStateException if {@code signingAlgorithm} is not {@code "Ed25519"}
     * @throws NullPointerException if {@code keyManager} or {@code signingAlgorithm} is {@code
     *     null}
     */
    public DefaultResultSigner(WorkerKeyManager keyManager, String signingAlgorithm) {
        Objects.requireNonNull(keyManager, "keyManager must not be null");
        Objects.requireNonNull(signingAlgorithm, "signingAlgorithm must not be null");
        if (!SUPPORTED_ALGORITHM.equals(signingAlgorithm)) {
            throw new IllegalStateException(
                    "V1 supports only Ed25519 signing algorithm (DEC-43 D4). "
                            + "Configured algorithm: "
                            + signingAlgorithm);
        }
        this.keyManager = keyManager;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Computes canonical bytes as {@code payload.resultPayloadJson().getBytes(UTF-8)} and
     * delegates to {@link WorkerKeyManager#signResult(byte[])}.
     *
     * @throws SigningException if the worker-lib signing operation fails
     * @throws NullPointerException if {@code payload} is {@code null}
     */
    @Override
    public byte[] signResult(SubmitResultPayload payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        byte[] canonicalBytes = payload.resultPayloadJson().getBytes(StandardCharsets.UTF_8);
        try {
            return keyManager.signResult(canonicalBytes);
        } catch (IllegalStateException e) {
            throw new SigningException("Failed to sign result payload: " + e.getMessage(), e);
        }
    }
}
