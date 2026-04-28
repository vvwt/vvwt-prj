package de.vvwt.slotopt.standalone.bootstrap;

/**
 * Exit code constants for the standalone-worker process (bootstrap and runtime phases).
 *
 * <p>Exit codes per E37S02 spec § (c) "Exit Codes" table:
 *
 * <ul>
 *   <li>{@code 75} = {@code EX_TEMPFAIL} — temporary failure (dispatcher unreachable); supervisor
 *       should restart
 *   <li>{@code 78} = {@code EX_CONFIG} — permanent error requiring manual intervention (algorithm
 *       not announced, algorithm deprecated past deadline, registration rejected with HTTP 410,
 *       result submission rejected via HTTP 410)
 *   <li>{@code 130} = {@code INTERRUPTED} — thread interrupted unexpectedly (POSIX 128+SIGINT)
 *   <li>{@code 0} = graceful shutdown
 * </ul>
 *
 * <p>Story: E41S04 AC-EXIT-CODE-BOOTSTRAP; E41S05 AC-EXIT-CODE-RUNTIME.
 */
public final class ExitCode {

    // =========================================================================
    // Bootstrap-phase exit codes (E41S04)
    // =========================================================================

    /** EX_TEMPFAIL (75) — dispatcher unreachable at startup; supervisor should restart. */
    public static final int DISPATCHER_UNREACHABLE = 75;

    /**
     * EX_CONFIG (78) — algorithm not in dispatcher's announced list; manual operator action
     * required.
     */
    public static final int ALGORITHM_NOT_ANNOUNCED = 78;

    /**
     * EX_CONFIG (78) — chosen algorithm's deprecation date is in the past; manual operator action
     * required.
     */
    public static final int ALGORITHM_DEPRECATED_PAST_DEADLINE = 78;

    /**
     * EX_CONFIG (78) — {@code POST /api/register-key} returned HTTP 410 (race-condition: algorithm
     * deprecated between announcement and registration); manual operator action required.
     */
    public static final int REGISTRATION_REJECTED_DEPRECATED = 78;

    // =========================================================================
    // Runtime-phase exit codes (E41S05)
    // =========================================================================

    /**
     * EX_CONFIG (78) — {@code POST /api/submit-result} returned HTTP 410 (rare race-condition:
     * algorithm deprecated past deadline between registration and a packet submission); manual
     * operator action required.
     *
     * <p>Per E37S02 spec § (c) "submit_rejected_deprecated" exit-code path.
     */
    public static final int SUBMIT_REJECTED_DEPRECATED = 78;

    /**
     * EX_TEMPFAIL (75) — dispatcher unreachable during the runtime polling loop (I/O failure or
     * repeated non-transient error); supervisor should restart.
     *
     * <p>Per E37S02 spec § (c) "dispatcher_unreachable" exit-code path (runtime variant).
     */
    public static final int DISPATCHER_UNREACHABLE_RUNTIME = 75;

    /**
     * INTERRUPTED (130) — worker thread was interrupted unexpectedly during a blocking operation
     * (e.g., {@code CpuThrottle.sleep}). POSIX convention: 128 + SIGINT(2) = 130.
     *
     * <p>Per E37S02 spec § (c) "interrupted" exit-code path.
     */
    public static final int INTERRUPTED = 130;

    private ExitCode() {}
}
