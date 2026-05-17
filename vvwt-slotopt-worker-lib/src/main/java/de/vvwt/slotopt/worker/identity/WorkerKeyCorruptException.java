// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.worker.identity;

import java.nio.file.Path;

/**
 * Thrown when the private key file exists but cannot be parsed as a valid Ed25519 private key.
 *
 * <p>Automatic recovery (regeneration) is intentionally prohibited: a corrupt-but-present key file
 * indicates that the worker's registered identity at the dispatcher may still be valid. Silent
 * regeneration would orphan that registered identity and break trust. Manual remediation is
 * required (delete the key file to trigger fresh generation on the next startup).
 *
 * <p>See DEC-6 (asymmetric-key registration) and Story E01S04 AC3.
 */
public class WorkerKeyCorruptException extends Exception {

    private final Path keyFilePath;

    /**
     * Constructs a new {@code WorkerKeyCorruptException}.
     *
     * @param keyFilePath the absolute path of the corrupt private key file
     * @param cause the underlying parsing exception
     */
    public WorkerKeyCorruptException(Path keyFilePath, Throwable cause) {
        super(
                "Worker private key file is corrupt and cannot be parsed — manual remediation"
                        + " required. File: "
                        + keyFilePath.toAbsolutePath()
                        + " Delete the file to trigger fresh keypair generation on next startup.",
                cause);
        this.keyFilePath = keyFilePath;
    }

    /**
     * Returns the path of the corrupt private key file.
     *
     * @return the absolute path of the corrupt key file
     */
    public Path getKeyFilePath() {
        return keyFilePath;
    }
}
