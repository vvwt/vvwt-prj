// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.standalone.integration;

import java.util.List;

/**
 * Result record returned by {@link WorkerLauncher#launch()} after worker thread completes.
 *
 * <p>Story: E41S06 — integration test infrastructure.
 *
 * @param exitCode the worker's exit code (0 = graceful, non-zero = error)
 * @param stderr captured standard error output from the worker
 * @param capturedEvents event names emitted via {@link CapturingStructuredLogger} and bootstrap
 *     event consumer
 */
public record WorkerRunResult(int exitCode, String stderr, List<String> capturedEvents) {}
