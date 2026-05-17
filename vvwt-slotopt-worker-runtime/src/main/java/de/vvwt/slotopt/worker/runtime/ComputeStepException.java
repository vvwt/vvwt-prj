package de.vvwt.slotopt.worker.runtime;

/**
 * Thrown when a single compute-step iteration fails unrecoverably.
 *
 * <p>Carries the exit code the calling worker should use when terminating the process. The calling
 * loop (e.g., standalone {@code DefaultWorkerLoop} or embedded worker) maps this to its own
 * lifecycle exception type.
 *
 * <p>Exit codes per E37S02 spec § (c) "Exit Codes":
 *
 * <ul>
 *   <li>{@code 78} — submit_rejected_deprecated: HTTP 410 on submit-result
 *   <li>{@code 75} — dispatcher_unreachable: I/O failure during pull or submit
 *   <li>{@code 130} — interrupted
 * </ul>
 *
 * <p>Story: E63S01 AC-ERR-SOLVE-FAILURE-PROPAGATION.
 */
public class ComputeStepException extends Exception {

    private final int exitCode;

    /**
     * Constructs a new {@code ComputeStepException}.
     *
     * @param exitCode exit code the process should use
     * @param message human-readable error description
     * @param cause the underlying cause, or {@code null}
     */
    public ComputeStepException(int exitCode, String message, Throwable cause) {
        super(message, cause);
        this.exitCode = exitCode;
    }

    /**
     * Returns the recommended process exit code.
     *
     * @return exit code (75 for dispatcher-unreachable, 78 for deprecated, 130 for interrupted)
     */
    public int getExitCode() {
        return exitCode;
    }
}
