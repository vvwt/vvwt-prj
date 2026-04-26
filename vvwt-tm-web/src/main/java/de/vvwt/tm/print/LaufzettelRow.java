package de.vvwt.tm.print;

/**
 * A single display row in a team's Laufzettel (team schedule) — fresh reconstruction per E24S02.
 *
 * <p>Implemented as a Java record for value-object semantics (AC-LAUFZETTEL-ROW-CREATE, E24S02):
 * immutability, structural equality, and compact accessor syntax. This replaces the legacy mutable
 * JavaBean {@code LaufzettelRow} which was deleted at E24S07 atomic cutover.
 *
 * <p>Each row represents one of the following:
 *
 * <ul>
 *   <li>A <strong>phase header</strong> ({@link #isPhaseHeader} = true) — separates phases in
 *       multi-phase schedules (AC11).
 *   <li>A <strong>break separator</strong> ({@link #isBreak} = true) — intra-phase break or section
 *       break (AC9).
 *   <li>A <strong>match round row</strong> — exactly one of {@link #isPlaying}, {@link
 *       #isRefereeing}, {@link #isActivity}, or {@link #isFree} is true (AC4–AC8).
 * </ul>
 *
 * <h2>Round state priority (AC6)</h2>
 *
 * <p>PLAYING &gt; REFEREEING &gt; ACTIVITY &gt; FREE. Determined by {@link
 * DefaultLaufzettelAssembler}.
 *
 * <h2>Mustache template compatibility</h2>
 *
 * <p>All String fields default to {@code ""} and all boolean fields default to {@code false}.
 * Record accessor names follow the {@code is*} / {@code get*} JavaBean convention by prefixing:
 * Mustache template access works via record component names directly.
 *
 * @param isPhaseHeader {@code true} if this row is a phase header
 * @param phaseHeaderName display name of the phase (e.g., "Vorrunde"); empty string if not a header
 * @param isBreak {@code true} if this row is a break separator
 * @param breakLabel display label for break rows (e.g., "Mittagspause"); empty if not a break
 * @param breakTimeWindow formatted time window for breaks (e.g., "12:00–12:30"); empty if absent
 * @param roundNumber round number (1-based); 0 for phase-header and break rows
 * @param timeWindow formatted time window for round rows (e.g., "10:00–10:15"); empty if no start
 *     time
 * @param isPlaying {@code true} if the team is playing in this round
 * @param opponentName name of the opposing team; non-empty only when {@code isPlaying}
 * @param fieldNumber field number as a display string; non-empty for PLAYING or REFEREEING
 * @param isRefereeing {@code true} if the team is refereeing in this round
 * @param isActivity {@code true} if the team has an assigned activity in this round
 * @param activityName name of the assigned activity; non-empty only when {@code isActivity}
 * @param isFree {@code true} if the team is free (no play, referee, or activity)
 * @see DefaultLaufzettelAssembler
 */
public record LaufzettelRow(
        boolean isPhaseHeader,
        String phaseHeaderName,
        boolean isBreak,
        String breakLabel,
        String breakTimeWindow,
        int roundNumber,
        String timeWindow,
        boolean isPlaying,
        String opponentName,
        String fieldNumber,
        boolean isRefereeing,
        boolean isActivity,
        String activityName,
        boolean isFree) {

    // -------------------------------------------------------------------------
    // Factory methods — canonical construction paths
    // -------------------------------------------------------------------------

    /**
     * Creates a phase header row.
     *
     * @param phaseHeaderName display name of the phase (e.g., "Vorrunde"); null treated as {@code
     *     ""}
     * @return an immutable phase header row
     */
    public static LaufzettelRow phaseHeader(String phaseHeaderName) {
        return new LaufzettelRow(
                true,
                phaseHeaderName != null ? phaseHeaderName : "",
                false,
                "",
                "",
                0,
                "",
                false,
                "",
                "",
                false,
                false,
                "",
                false);
    }

    /**
     * Creates a break separator row.
     *
     * @param label display label (e.g., "Mittagspause"); null treated as {@code ""}
     * @param timeWindow formatted time window, or {@code ""} if no start time; null treated as
     *     {@code ""}
     * @return an immutable break separator row
     */
    public static LaufzettelRow breakRow(String label, String timeWindow) {
        return new LaufzettelRow(
                false,
                "",
                true,
                label != null ? label : "",
                timeWindow != null ? timeWindow : "",
                0,
                "",
                false,
                "",
                "",
                false,
                false,
                "",
                false);
    }

    /**
     * Creates a PLAYING round row.
     *
     * @param roundNumber round number (1-based)
     * @param timeWindow formatted time window, or {@code ""}; null treated as {@code ""}
     * @param opponentName name of the opposing team; null treated as {@code ""}
     * @param fieldNumber field number as a display string; null treated as {@code ""}
     * @return an immutable playing row
     */
    public static LaufzettelRow playing(
            int roundNumber, String timeWindow, String opponentName, String fieldNumber) {
        return new LaufzettelRow(
                false,
                "",
                false,
                "",
                "",
                roundNumber,
                timeWindow != null ? timeWindow : "",
                true,
                opponentName != null ? opponentName : "",
                fieldNumber != null ? fieldNumber : "",
                false,
                false,
                "",
                false);
    }

    /**
     * Creates a REFEREEING round row.
     *
     * @param roundNumber round number (1-based)
     * @param timeWindow formatted time window, or {@code ""}; null treated as {@code ""}
     * @param fieldNumber field number as a display string; null treated as {@code ""}
     * @return an immutable refereeing row
     */
    public static LaufzettelRow refereeing(int roundNumber, String timeWindow, String fieldNumber) {
        return new LaufzettelRow(
                false,
                "",
                false,
                "",
                "",
                roundNumber,
                timeWindow != null ? timeWindow : "",
                false,
                "",
                fieldNumber != null ? fieldNumber : "",
                true,
                false,
                "",
                false);
    }

    /**
     * Creates an ACTIVITY round row.
     *
     * @param roundNumber round number (1-based)
     * @param timeWindow formatted time window, or {@code ""}; null treated as {@code ""}
     * @param activityName name of the assigned activity; null treated as {@code ""}
     * @return an immutable activity row
     */
    public static LaufzettelRow activity(int roundNumber, String timeWindow, String activityName) {
        return new LaufzettelRow(
                false,
                "",
                false,
                "",
                "",
                roundNumber,
                timeWindow != null ? timeWindow : "",
                false,
                "",
                "",
                false,
                true,
                activityName != null ? activityName : "",
                false);
    }

    /**
     * Creates a FREE round row.
     *
     * @param roundNumber round number (1-based)
     * @param timeWindow formatted time window, or {@code ""}; null treated as {@code ""}
     * @return an immutable free row
     */
    public static LaufzettelRow free(int roundNumber, String timeWindow) {
        return new LaufzettelRow(
                false,
                "",
                false,
                "",
                "",
                roundNumber,
                timeWindow != null ? timeWindow : "",
                false,
                "",
                "",
                false,
                false,
                "",
                true);
    }
}
