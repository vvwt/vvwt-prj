// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.standalone;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Immutable configuration record for the vvwt-slotopt-standalone-worker process.
 *
 * <p>All fields are populated by {@link WorkerConfigLoader} from Picocli-parsed CLI arguments.
 *
 * <p>Field provenance:
 *
 * <ul>
 *   <li>{@code dispatcherUrl} — per E37S02 spec § (c) CLI Argument Schema, E01S05 AC1
 *   <li>{@code keyDir} — per E37S02 spec § (c) (worker-lib WorkerKeyManager root directory)
 *   <li>{@code pollInterval} — per E37S02 spec § (c), E01S05 AC3; default 30 s
 *   <li>{@code signingAlgorithm} — per DEC-43 D4 / Brief D-6 / E37S02 spec § (c); V1 default {@code
 *       "Ed25519"}
 *   <li>{@code httpTimeout} — per E37S02 spec § (c); default 30 s
 *   <li>{@code cpuThrottlePercent} — per E37S02 spec § (c), E01S05 AC1; default 50
 *   <li>{@code workerName} — per E37S02 spec § (c), E01S05 AC1; optional, used in audit logs
 *   <li>{@code logFormat} — per E37S02 spec § (c), E01S05 AC8; {@code "text"} or {@code "json"}
 * </ul>
 *
 * @param dispatcherUrl Dispatcher base URL (required). Per E37S02 spec § (c), E01S05 AC1.
 * @param keyDir Worker key storage directory (required). Per E37S02 spec § (c).
 * @param pollInterval Idle-poll interval (default 30 s). Per E37S02 spec § (c), E01S05 AC3.
 * @param signingAlgorithm Signing algorithm identifier (default {@code "Ed25519"}). Per DEC-43 D4
 *     V1 single-algorithm set; only {@code "Ed25519"} is accepted in V1. Validated by {@link
 *     WorkerConfigLoader}.
 * @param httpTimeout HTTP client timeout (default 30 s). Per E37S02 spec § (c).
 * @param cpuThrottlePercent CPU throttle target percentage 1–100 (default 50). Per E37S02 spec §
 *     (c), E01S05 AC1.
 * @param workerName Optional worker name for audit log identification. Per E01S05 AC1. May be
 *     {@code null}.
 * @param logFormat Log output format: {@code "text"} (default) or {@code "json"}. Per E01S05 AC8.
 * @see WorkerConfigLoader
 * @see de.vvwt.slotopt.standalone.internal.DefaultWorkerConfigLoader
 */
public record WorkerConfig(
        URI dispatcherUrl,
        Path keyDir,
        Duration pollInterval,
        String signingAlgorithm,
        Duration httpTimeout,
        int cpuThrottlePercent,
        String workerName,
        String logFormat) {}
