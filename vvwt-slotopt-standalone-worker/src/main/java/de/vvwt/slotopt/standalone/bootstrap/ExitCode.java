package de.vvwt.slotopt.standalone.bootstrap;

/**
 * Exit code constants for the standalone-worker bootstrap phase.
 *
 * <p>Exit codes per E37S02 spec § (c) "Exit Codes" table:
 *
 * <ul>
 *   <li>{@code 75} = {@code EX_TEMPFAIL} — temporary failure (dispatcher unreachable); supervisor
 *       should restart
 *   <li>{@code 78} = {@code EX_CONFIG} — permanent error requiring manual intervention (algorithm
 *       not announced, algorithm deprecated past deadline, registration rejected with HTTP 410)
 * </ul>
 *
 * <p>Story: E41S04 AC-EXIT-CODE-BOOTSTRAP.
 */
public final class ExitCode {

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

    private ExitCode() {}
}
