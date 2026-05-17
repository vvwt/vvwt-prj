// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.infoportal;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;

/**
 * Ed25519 keypair manager with NO-PLAINTEXT-ON-DISK invariant (AC8).
 *
 * <p>On first call to {@link #initializeIfAbsent()}, generates an Ed25519 keypair using JDK 21
 * native {@code KeyPairGenerator.getInstance("Ed25519")}. The private key is encrypted at rest
 * using AES-256-GCM.
 *
 * <p>DEC-58 Clause A + DEC-72 Clause A-ext: every self-created Spring component — including
 * {@code @Bean}-factory-produced first-party service beans — must have a public interface in the
 * bounded-context root package.
 *
 * @see de.vvwt.tm.infoportal.internal.DefaultEd25519KeypairManager
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E38S09.story.md">E38S09
 *     AC8</a>
 * @see <a href="../../../../../../../../.gaai/project/contexts/memory/decisions/DEC-6.md">DEC-6 —
 *     asymmetric-key registration</a>
 * @since E57S05 (DEC-58/DEC-72 interface extraction)
 */
public interface Ed25519KeypairManager {

    /**
     * Initializes the keypair if key files are absent; loads from disk if they exist.
     *
     * @throws GeneralSecurityException if key generation or decryption fails
     * @throws IOException if file I/O fails
     */
    void initializeIfAbsent() throws GeneralSecurityException, IOException;

    /**
     * Returns the Ed25519 public key. Call {@link #initializeIfAbsent()} first.
     *
     * @return the public key
     */
    PublicKey getPublicKey();

    /**
     * Returns the Ed25519 private key (in-memory only). Call {@link #initializeIfAbsent()} first.
     *
     * @return the private key
     */
    PrivateKey getPrivateKey();

    /**
     * Signs the given payload bytes with the Ed25519 private key.
     *
     * @param payload bytes to sign
     * @return 64-byte Ed25519 signature
     * @throws GeneralSecurityException if signing fails
     */
    byte[] sign(byte[] payload) throws GeneralSecurityException;
}
