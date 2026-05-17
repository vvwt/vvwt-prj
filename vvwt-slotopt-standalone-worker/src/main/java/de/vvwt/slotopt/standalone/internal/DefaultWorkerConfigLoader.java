// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.standalone.internal;

import de.vvwt.slotopt.standalone.WorkerConfig;
import de.vvwt.slotopt.standalone.WorkerConfigLoader;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Default implementation of {@link WorkerConfigLoader} using Picocli to parse CLI arguments.
 *
 * <p>Resides in {@code de.vvwt.slotopt.standalone.internal} per DEC-35-by-analogy (E35S04
 * precedent): implementations live in the {@code .internal} package; the public interface {@link
 * WorkerConfigLoader} is the only type visible outside this package.
 *
 * <p>Picocli parsing strategy: {@code DefaultWorkerConfigLoader} itself carries the
 * {@code @Command} and {@code @Option} annotations; {@link #load(String[])} constructs a {@link
 * CommandLine} over {@code this} and calls {@code CommandLine.parseArgs()} (not {@code execute()})
 * to avoid triggering System.exit. Missing required options cause a {@link
 * CommandLine.MissingParameterException}; parse errors cause {@link
 * CommandLine.ParameterException}.
 *
 * @see WorkerConfigLoader
 */
@Command(
        name = "vvwt-slotopt-standalone-worker",
        mixinStandardHelpOptions = true,
        description = "VVW Slot-Opt Standalone Worker — headless compute process")
public class DefaultWorkerConfigLoader implements WorkerConfigLoader {

    /** V1 supported signing algorithm set per DEC-43 D4. */
    private static final Set<String> V1_SUPPORTED_ALGORITHMS = Set.of("Ed25519");

    // =========================================================================
    // Required options
    // =========================================================================

    /** Dispatcher base URL. Required per E37S02 spec § (c) CLI Argument Schema, E01S05 AC1. */
    @Option(
            names = {"--dispatcher-url"},
            required = true,
            description = "Dispatcher base URL (required). Per E37S02 spec § (c), E01S05 AC1.",
            paramLabel = "<url>")
    private URI dispatcherUrl;

    /** Key storage directory (worker-lib WorkerKeyManager root). Required per E37S02 spec § (c). */
    @Option(
            names = {"--key-dir"},
            required = true,
            description =
                    "Worker key storage directory — WorkerKeyManager root. Per E37S02 spec § (c).",
            paramLabel = "<path>")
    private Path keyDir;

    // =========================================================================
    // Optional options (with defaults)
    // =========================================================================

    /**
     * Idle-poll interval. Default 30 s per E37S02 spec § (c) (was {@code --idle-poll-seconds} in
     * E01S05 AC3; renamed to ISO-8601 Duration for type safety in E41S02).
     */
    @Option(
            names = {"--poll-interval"},
            defaultValue = "PT30S",
            description =
                    "Idle-poll interval as ISO-8601 Duration (default PT30S). Per E37S02 spec §"
                            + " (c), E01S05 AC3.",
            paramLabel = "<duration>")
    private Duration pollInterval;

    /**
     * Signing algorithm identifier. Default {@code "Ed25519"} per DEC-43 D4 / Brief D-6. V1 accepts
     * only {@code "Ed25519"}; other values are rejected by post-parse validation.
     */
    @Option(
            names = {"--signing-algorithm"},
            defaultValue = "Ed25519",
            description =
                    "Signing algorithm identifier (default: Ed25519). V1 accepts only 'Ed25519'"
                            + " per DEC-43 D4. Per E37S02 spec § (c).",
            paramLabel = "<id>")
    private String signingAlgorithm;

    /** HTTP client timeout. Default 30 s per E37S02 spec § (c). */
    @Option(
            names = {"--http-timeout"},
            defaultValue = "PT30S",
            description =
                    "HTTP client timeout as ISO-8601 Duration (default PT30S). Per E37S02"
                            + " spec § (c).",
            paramLabel = "<duration>")
    private Duration httpTimeout;

    /** CPU throttle target percentage 1–100. Default 50 per E37S02 spec § (c), E01S05 AC1. */
    @Option(
            names = {"--max-cpu-percent"},
            defaultValue = "50",
            description = "CPU throttle target percentage 1-100 (default 50). Per E01S05 AC1.",
            paramLabel = "<int>")
    private int cpuThrottlePercent;

    /** Optional worker name for audit log identification. Per E01S05 AC1. */
    @Option(
            names = {"--name"},
            description = "Worker name for audit log identification (optional). Per E01S05 AC1.",
            paramLabel = "<string>")
    private String workerName;

    /** Log output format. Default {@code "text"} per E01S05 AC8. */
    @Option(
            names = {"--log-format"},
            defaultValue = "text",
            description = "Log output format: text (default) or json. Per E01S05 AC8.",
            paramLabel = "<json|text>")
    private String logFormat;

    // =========================================================================
    // WorkerConfigLoader implementation
    // =========================================================================

    /** {@inheritDoc} */
    @Override
    public WorkerConfig load(String[] args) {
        CommandLine cmd = new CommandLine(this);
        try {
            cmd.parseArgs(args);
        } catch (CommandLine.ParameterException e) {
            // Emit Picocli usage to stderr per AC-FAIL-FAST-MISSING-REQUIRED
            // MissingParameterException is a subclass of ParameterException — catching the
            // superclass covers both missing-required and general parse errors.
            cmd.usage(System.err);
            throw new IllegalStateException(
                    "CLI argument parse error (exit 2): " + e.getMessage(), e);
        }

        // Post-parse V1 signing-algorithm validation per DEC-43 D4 / AC-SIGNING-ALGORITHM-FLAG
        if (!V1_SUPPORTED_ALGORITHMS.contains(signingAlgorithm)) {
            throw new IllegalArgumentException(
                    "Unsupported --signing-algorithm value: '"
                            + signingAlgorithm
                            + "'. V1 supported set: "
                            + V1_SUPPORTED_ALGORITHMS
                            + ". See DEC-43 D4 for V1 algorithm-set rationale.");
        }

        return new WorkerConfig(
                dispatcherUrl,
                keyDir,
                pollInterval,
                signingAlgorithm,
                httpTimeout,
                cpuThrottlePercent,
                workerName,
                logFormat);
    }
}
