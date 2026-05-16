// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.worker.identity;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Thrown when keypair files for BOTH the configured algorithm AND a different algorithm are found
 * in the data directory at startup (D-4 mixed-state scenario).
 *
 * <p>This is an unrecoverable startup condition. The operator must manually remove conflicting key
 * files before the worker can start. Auto-recovery is explicitly forbidden for this scenario (Q-5:
 * defensive fail-safe over silent key disposal — DEC-6).
 *
 * <p>Remediation: Inspect the listed files, remove the files for the algorithm you no longer want,
 * then restart the worker.
 *
 * <p>See Story E37S03, DEC-6, AC-D4-MIXED-STATE-REFUSE.
 */
public final class MixedAlgorithmKeysException extends RuntimeException {

    private final List<Path> conflictingFiles;

    /**
     * Constructs a {@code MixedAlgorithmKeysException}.
     *
     * @param conflictingFiles the list of all key files found in the data directory (from multiple
     *     algorithms); must not be {@code null}
     */
    public MixedAlgorithmKeysException(List<Path> conflictingFiles) {
        super(buildMessage(conflictingFiles));
        this.conflictingFiles = List.copyOf(conflictingFiles);
    }

    /**
     * Returns the list of conflicting key files found in the data directory.
     *
     * @return unmodifiable list of paths; never {@code null}
     */
    public List<Path> getConflictingFiles() {
        return conflictingFiles;
    }

    private static String buildMessage(List<Path> conflictingFiles) {
        String fileList =
                conflictingFiles.stream()
                        .map(p -> "  " + p.toAbsolutePath())
                        .collect(Collectors.joining("\n"));
        return "Mixed algorithm keypair files detected — worker refuses to start (D-4 mechanic).\n"
                + "Conflicting files found:\n"
                + fileList
                + "\n"
                + "Remediation: manually remove the key files for the algorithm you no longer want,"
                + " then restart the worker. Auto-recovery is disabled for this scenario to prevent"
                + " accidental key disposal (DEC-6, E37S03 AC-D4-MIXED-STATE-REFUSE).";
    }
}
