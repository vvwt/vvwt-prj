package de.vvwt.tm.infrastructure.print;

/**
 * A single row in a team's Laufzettel (team schedule).
 *
 * <p>This POJO is passed to jmustache as a list element. jmustache accesses each
 * field by name (getter-based), so standard JavaBean getters are required.
 *
 * <p>Each row represents one of the following:
 * <ul>
 *   <li>A phase header ({@link #isPhaseHeader} = true) — separates phases in multi-phase schedules</li>
 *   <li>A break separator ({@link #isBreak} = true) — intra-phase break or section break</li>
 *   <li>A match round row — exactly one of {@link #isPlaying}, {@link #isRefereeing},
 *       {@link #isActivity}, or {@link #isFree} is true</li>
 * </ul>
 *
 * <h2>Round state priority (AC6 — E08S08)</h2>
 * <p>The assembler determines each team's state per round following:
 * PLAYING &gt; REFEREEING &gt; ACTIVITY &gt; FREE. By E03S10 / E08S04 design,
 * PLAYING and REFEREEING are mutually exclusive, and ACTIVITY is only assigned to free rounds,
 * so this priority is a safety-net, not a common disambiguation.
 *
 * <h2>jmustache strict-mode compatibility</h2>
 * <p>All String fields default to {@code ""} and all boolean fields default to {@code false}.
 * This prevents {@code MustacheException} caused by absent keys in strict mode (documented in
 * E08S07 QA report D2).
 *
 * @see LaufzettelAssembler
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E08S08.story.md">Story E08S08</a>
 */
public class LaufzettelRow {

    // -------------------------------------------------------------------------
    // Phase header row — separates phases in multi-phase schedules (AC11)
    // -------------------------------------------------------------------------

    /** {@code true} if this row is a phase header (not a round or break row). */
    private boolean isPhaseHeader = false;

    /** Phase name shown in the header row (e.g., "Vorrunde"). Empty string if not a phase header. */
    private String phaseHeaderName = "";

    // -------------------------------------------------------------------------
    // Break separator row (AC9 — intra-phase break / section break)
    // -------------------------------------------------------------------------

    /** {@code true} if this row is a break separator (not a round or phase header row). */
    private boolean isBreak = false;

    /** Display label for break rows (e.g., "Mittagspause" or "Pause"). Empty string if not a break. */
    private String breakLabel = "";

    /** Break time window (e.g., "12:00–12:30"). Empty string if not a break or no start time. */
    private String breakTimeWindow = "";

    // -------------------------------------------------------------------------
    // Round row — common fields (AC4–AC8)
    // -------------------------------------------------------------------------

    /** Round number (1-based). 0 for phase header and break rows. */
    private int roundNumber = 0;

    /**
     * Formatted time window for this round (e.g., "10:00–10:15").
     * Empty string if the tournament has no {@code plannedStartTime} (AC10).
     */
    private String timeWindow = "";

    // -------------------------------------------------------------------------
    // Round row — state flags (AC4–AC8, AC6 priority)
    // -------------------------------------------------------------------------

    /** {@code true} if the team is playing in this round (highest priority). */
    private boolean isPlaying = false;

    /** Name of the opposing team. Non-empty only when {@code isPlaying} is true (AC4). */
    private String opponentName = "";

    /** Field number as a display string. Non-empty only when {@code isPlaying} or {@code isRefereeing} (AC4, AC5). */
    private String fieldNumber = "";

    /** {@code true} if the team is refereeing in this round. */
    private boolean isRefereeing = false;

    /** {@code true} if the team has an assigned activity in this round (e.g., "Mannschaftsfoto"). */
    private boolean isActivity = false;

    /** Name of the assigned activity. Non-empty only when {@code isActivity} is true (AC7). */
    private String activityName = "";

    /** {@code true} if the team is free (no play, referee, or activity) in this round. */
    private boolean isFree = false;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /** Default constructor — all fields at their default (empty string / false / 0) values. */
    public LaufzettelRow() {
    }

    // -------------------------------------------------------------------------
    // Factory methods for readability
    // -------------------------------------------------------------------------

    /**
     * Creates a phase header row.
     *
     * @param phaseHeaderName display name of the phase (e.g., "Vorrunde")
     * @return a phase header row
     */
    public static LaufzettelRow phaseHeader(String phaseHeaderName) {
        LaufzettelRow row = new LaufzettelRow();
        row.isPhaseHeader = true;
        row.phaseHeaderName = phaseHeaderName != null ? phaseHeaderName : "";
        return row;
    }

    /**
     * Creates a break separator row.
     *
     * @param label       display label (e.g., "Mittagspause" or "Pause")
     * @param timeWindow  formatted time window, or {@code ""} if no start time
     * @return a break separator row
     */
    public static LaufzettelRow breakRow(String label, String timeWindow) {
        LaufzettelRow row = new LaufzettelRow();
        row.isBreak = true;
        row.breakLabel = label != null ? label : "";
        row.breakTimeWindow = timeWindow != null ? timeWindow : "";
        return row;
    }

    /**
     * Creates a PLAYING round row.
     *
     * @param roundNumber  round number (1-based)
     * @param timeWindow   formatted time window, or {@code ""}
     * @param opponentName name of the opposing team
     * @param fieldNumber  field number as a display string
     * @return a playing round row
     */
    public static LaufzettelRow playing(int roundNumber, String timeWindow,
                                        String opponentName, String fieldNumber) {
        LaufzettelRow row = new LaufzettelRow();
        row.roundNumber = roundNumber;
        row.timeWindow = timeWindow != null ? timeWindow : "";
        row.isPlaying = true;
        row.opponentName = opponentName != null ? opponentName : "";
        row.fieldNumber = fieldNumber != null ? fieldNumber : "";
        return row;
    }

    /**
     * Creates a REFEREEING round row.
     *
     * @param roundNumber round number (1-based)
     * @param timeWindow  formatted time window, or {@code ""}
     * @param fieldNumber field number as a display string
     * @return a refereeing round row
     */
    public static LaufzettelRow refereeing(int roundNumber, String timeWindow, String fieldNumber) {
        LaufzettelRow row = new LaufzettelRow();
        row.roundNumber = roundNumber;
        row.timeWindow = timeWindow != null ? timeWindow : "";
        row.isRefereeing = true;
        row.fieldNumber = fieldNumber != null ? fieldNumber : "";
        return row;
    }

    /**
     * Creates an ACTIVITY round row.
     *
     * @param roundNumber  round number (1-based)
     * @param timeWindow   formatted time window, or {@code ""}
     * @param activityName name of the assigned activity
     * @return an activity round row
     */
    public static LaufzettelRow activity(int roundNumber, String timeWindow, String activityName) {
        LaufzettelRow row = new LaufzettelRow();
        row.roundNumber = roundNumber;
        row.timeWindow = timeWindow != null ? timeWindow : "";
        row.isActivity = true;
        row.activityName = activityName != null ? activityName : "";
        return row;
    }

    /**
     * Creates a FREE round row.
     *
     * @param roundNumber round number (1-based)
     * @param timeWindow  formatted time window, or {@code ""}
     * @return a free round row
     */
    public static LaufzettelRow free(int roundNumber, String timeWindow) {
        LaufzettelRow row = new LaufzettelRow();
        row.roundNumber = roundNumber;
        row.timeWindow = timeWindow != null ? timeWindow : "";
        row.isFree = true;
        return row;
    }

    // -------------------------------------------------------------------------
    // Accessors — required by jmustache (getter-based property access)
    // -------------------------------------------------------------------------

    public boolean isPhaseHeader() { return isPhaseHeader; }
    public String getPhaseHeaderName() { return phaseHeaderName; }

    public boolean isBreak() { return isBreak; }
    public String getBreakLabel() { return breakLabel; }
    public String getBreakTimeWindow() { return breakTimeWindow; }

    public int getRoundNumber() { return roundNumber; }
    public String getTimeWindow() { return timeWindow; }

    public boolean isPlaying() { return isPlaying; }
    public String getOpponentName() { return opponentName; }
    public String getFieldNumber() { return fieldNumber; }

    public boolean isRefereeing() { return isRefereeing; }

    public boolean isActivity() { return isActivity; }
    public String getActivityName() { return activityName; }

    public boolean isFree() { return isFree; }
}
