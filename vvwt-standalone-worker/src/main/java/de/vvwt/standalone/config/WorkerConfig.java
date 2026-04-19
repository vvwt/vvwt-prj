package de.vvwt.standalone.config;

import java.nio.file.Path;

/**
 * Validated configuration record for the standalone optimizer worker.
 *
 * <p>All fields are immutable after construction. Validation is performed at construction time; the
 * constructor throws {@link IllegalArgumentException} for invalid combinations.
 *
 * <p>Implements Story E01S05 AC1.
 *
 * @param dataDir directory for keypair and state files
 * @param dispatcherUrl base URL of the dispatcher service (required)
 * @param maxCpuPercent maximum CPU utilisation percentage, 1–100 (default 50)
 * @param name optional human-readable label for audit logs
 * @param idlePollSeconds seconds to sleep when no packet is available (default 30)
 * @param logFormatJson when true, emit structured JSON log lines (--log-format json)
 */
public record WorkerConfig(
        Path dataDir,
        String dispatcherUrl,
        int maxCpuPercent,
        String name,
        int idlePollSeconds,
        boolean logFormatJson) {

    /** Default CPU utilisation cap (50%). */
    public static final int DEFAULT_MAX_CPU_PERCENT = 50;

    /** Default idle polling interval in seconds. */
    public static final int DEFAULT_IDLE_POLL_SECONDS = 30;

    /** Compact canonical constructor — validates all invariants. */
    public WorkerConfig {
        if (dataDir == null) {
            throw new IllegalArgumentException("dataDir must not be null");
        }
        if (dispatcherUrl == null || dispatcherUrl.isBlank()) {
            throw new IllegalArgumentException("dispatcherUrl is required");
        }
        if (maxCpuPercent < 1 || maxCpuPercent > 100) {
            throw new IllegalArgumentException(
                    "maxCpuPercent must be in [1, 100], got: " + maxCpuPercent);
        }
        if (idlePollSeconds < 1) {
            throw new IllegalArgumentException(
                    "idlePollSeconds must be >= 1, got: " + idlePollSeconds);
        }
        // Normalize dispatcherUrl: strip trailing slash for predictable URL construction
        dispatcherUrl =
                dispatcherUrl.endsWith("/")
                        ? dispatcherUrl.substring(0, dispatcherUrl.length() - 1)
                        : dispatcherUrl;
    }
}
