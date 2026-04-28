package de.vvwt.slotopt.standalone.bootstrap;

/**
 * Thrown when the worker bootstrap phase cannot complete successfully.
 *
 * <p>Carries the exit code the caller should use when terminating the process (per {@link
 * ExitCode}).
 *
 * <p>Story: E41S04 AC-EXIT-CODE-BOOTSTRAP.
 */
public class BootstrapException extends RuntimeException {

    private final int exitCode;

    /**
     * Constructs a new {@code BootstrapException}.
     *
     * @param exitCode exit code the process should use ({@link ExitCode#DISPATCHER_UNREACHABLE} or
     *     {@link ExitCode#ALGORITHM_NOT_ANNOUNCED} etc.)
     * @param message human-readable error description
     * @param cause the underlying cause, or {@code null}
     */
    public BootstrapException(int exitCode, String message, Throwable cause) {
        super(message, cause);
        this.exitCode = exitCode;
    }

    /**
     * Returns the process exit code that should be used when this exception terminates the worker.
     *
     * @return exit code (75 for EX_TEMPFAIL, 78 for EX_CONFIG)
     */
    public int getExitCode() {
        return exitCode;
    }
}
