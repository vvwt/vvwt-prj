package de.vvwt.slotopt.standalone.runtime;

/**
 * Thrown when the runtime polling loop terminates abnormally.
 *
 * <p>Carries the exit code the caller should use when terminating the process (per {@link
 * de.vvwt.slotopt.standalone.bootstrap.ExitCode}). Mirrors {@link
 * de.vvwt.slotopt.standalone.bootstrap.BootstrapException} in the bootstrap phase.
 *
 * <p>Exit codes per E37S02 spec § (c) "Exit Codes":
 *
 * <ul>
 *   <li>{@code 78} — submit_rejected_deprecated: HTTP 410 on submit-result
 *   <li>{@code 75} — dispatcher_unreachable_runtime: I/O failure during polling
 *   <li>{@code 130} — interrupted: thread interrupted unexpectedly
 *   <li>{@code 0} — graceful shutdown
 * </ul>
 *
 * <p>Story: E41S05 AC-EXIT-CODE-RUNTIME, AC-CPU-THROTTLE.
 */
public class WorkerLoopException extends RuntimeException {

    private final int exitCode;

    /**
     * Constructs a new {@code WorkerLoopException}.
     *
     * @param exitCode exit code the process should use
     * @param message human-readable error description
     * @param cause the underlying cause, or {@code null}
     */
    public WorkerLoopException(int exitCode, String message, Throwable cause) {
        super(message, cause);
        this.exitCode = exitCode;
    }

    /**
     * Returns the process exit code.
     *
     * @return exit code (75 for EX_TEMPFAIL, 78 for EX_CONFIG, 130 for interrupted, 0 for graceful)
     */
    public int getExitCode() {
        return exitCode;
    }
}
