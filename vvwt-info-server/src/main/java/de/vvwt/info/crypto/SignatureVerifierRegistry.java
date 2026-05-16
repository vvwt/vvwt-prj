// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.info.crypto;

/**
 * Resolves the appropriate {@link SignatureVerifier} by {@code algorithm_id} (AC6 / DEC-43 D4).
 *
 * <p>The registry is algorithm-agnostic — it dispatches to whichever verifier was registered for
 * the given {@code algorithm_id}. Phase-1 contains exactly {@code Ed25519}, backed by {@link
 * de.vvwt.info.crypto.internal.Ed25519SignatureVerifier}. Future ML-DSA / SLH-DSA verifiers are
 * registered under their respective {@code algorithm_id} values without changing this interface or
 * the {@link SignatureVerifier} interface.
 *
 * @see SignatureVerifier
 * @see de.vvwt.info.crypto.internal.DefaultSignatureVerifierRegistry
 * @see de.vvwt.info.crypto.internal.Ed25519SignatureVerifier
 * @see <a href="../../../../../../../docs/governance/stories/E38S04.story.md">E38S04 AC6</a>
 * @see <a href="../../../../../../../docs/governance/decisions/DEC-43.md">DEC-43 D4</a>
 */
public interface SignatureVerifierRegistry {

    /**
     * Resolves the verifier for the given {@code algorithm_id}.
     *
     * @param algorithmId the algorithm identifier (e.g., {@code "Ed25519"})
     * @return the registered verifier
     * @throws IllegalArgumentException if {@code algorithmId} is null or not registered
     */
    SignatureVerifier resolve(String algorithmId);
}
