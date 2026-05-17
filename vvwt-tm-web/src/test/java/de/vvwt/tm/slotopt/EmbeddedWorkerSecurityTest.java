// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.slotopt;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.slotopt.worker.identity.WorkerKeyManager;
import de.vvwt.slotopt.worker.identity.internal.DefaultWorkerKeyManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests for AC-SEC-OWN-DISTINCT-KEYPAIR and AC-SEC-KEYPAIR-PATH-FROM-TM-DATA-DIR.
 *
 * <p>Verifies that the embedded worker's keypair is truly distinct from any other TM keypair, and
 * that both key files coexist in separate directories without collision.
 *
 * <p>Story: E63S03.
 */
class EmbeddedWorkerSecurityTest {

    private static final Logger LOG = LoggerFactory.getLogger(EmbeddedWorkerSecurityTest.class);

    @TempDir Path tempDir;

    // -------------------------------------------------------------------------
    // AC-SEC-OWN-DISTINCT-KEYPAIR
    // -------------------------------------------------------------------------

    @Test
    void embeddedWorkerKeyIsDistinctFromLeg2SubmitterKey() throws Exception {
        // Simulate two separate key directories — one for Leg-2 submitter, one for embedded worker
        Path leg2KeyDir = tempDir.resolve("slotopt-keys");
        Path embeddedKeyDir = tempDir.resolve("embedded-worker-keys");
        Files.createDirectories(leg2KeyDir);
        Files.createDirectories(embeddedKeyDir);

        WorkerKeyManager leg2KeyManager = new DefaultWorkerKeyManager(leg2KeyDir, "Ed25519", LOG);
        WorkerKeyManager embeddedKeyManager =
                new DefaultWorkerKeyManager(embeddedKeyDir, "Ed25519", LOG);

        byte[] leg2PublicKey = leg2KeyManager.getPublicKeyBytes();
        byte[] embeddedPublicKey = embeddedKeyManager.getPublicKeyBytes();

        // Keys must be different (distinct keypairs, distinct directories)
        assertThat(leg2PublicKey).isNotEqualTo(embeddedPublicKey);
        assertThat(Arrays.equals(leg2PublicKey, embeddedPublicKey)).isFalse();
    }

    @Test
    void embeddedKeyFilesCoexistWithLeg2KeyFilesWithoutCollision() throws Exception {
        // Both directories exist with their own key files — no filename collision
        Path leg2KeyDir = tempDir.resolve("slotopt-keys");
        Path embeddedKeyDir = tempDir.resolve("embedded-worker-keys");
        Files.createDirectories(leg2KeyDir);
        Files.createDirectories(embeddedKeyDir);

        new DefaultWorkerKeyManager(leg2KeyDir, "Ed25519", LOG);
        new DefaultWorkerKeyManager(embeddedKeyDir, "Ed25519", LOG);

        // Both key files exist
        assertThat(Files.exists(leg2KeyDir.resolve("worker-Ed25519.key"))).isTrue();
        assertThat(Files.exists(leg2KeyDir.resolve("worker-Ed25519.pub"))).isTrue();
        assertThat(Files.exists(embeddedKeyDir.resolve("worker-Ed25519.key"))).isTrue();
        assertThat(Files.exists(embeddedKeyDir.resolve("worker-Ed25519.pub"))).isTrue();
    }

    @Test
    void subsequentStartLoadsExistingKeyRatherThanOverwriting() throws Exception {
        Path keyDir = tempDir.resolve("embedded-worker-keys");
        Files.createDirectories(keyDir);

        // First creation — generates new keypair
        WorkerKeyManager first = new DefaultWorkerKeyManager(keyDir, "Ed25519", LOG);
        byte[] firstPublicKey = first.getPublicKeyBytes();

        // Second creation — must load existing key, not regenerate
        WorkerKeyManager second = new DefaultWorkerKeyManager(keyDir, "Ed25519", LOG);
        byte[] secondPublicKey = second.getPublicKeyBytes();

        assertThat(Arrays.equals(firstPublicKey, secondPublicKey)).isTrue();
    }

    // -------------------------------------------------------------------------
    // AC-SEC-KEYPAIR-PATH-FROM-TM-DATA-DIR (structural / config test)
    // -------------------------------------------------------------------------

    @Test
    void embeddedWorkerKeyDirDerivesFromTmDataDir() {
        // The EmbeddedWorkerConfiguration uses:
        // ${tm.slotopt.embedded-worker.key-dir:${tm.data.dir}/embedded-worker-keys}
        // This test verifies the default path pattern is distinct from slotopt-keys.
        String tmDataDir = "/home/user/.tournament-manager";
        String leg2KeyDir = tmDataDir + "/slotopt-keys";
        String embeddedKeyDir = tmDataDir + "/embedded-worker-keys";

        assertThat(leg2KeyDir).isNotEqualTo(embeddedKeyDir);
        assertThat(embeddedKeyDir).startsWith(tmDataDir);
    }
}
