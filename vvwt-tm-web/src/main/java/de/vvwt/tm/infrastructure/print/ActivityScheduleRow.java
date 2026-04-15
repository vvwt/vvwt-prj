package de.vvwt.tm.infrastructure.print;

import java.util.Collections;
import java.util.List;

/**
 * A single row in the Mannschaftsfoto-Übersicht (activity schedule) print table.
 *
 * <p>This POJO is serialized to jmustache as a list element. jmustache accesses each
 * field by name (getter-based), so standard JavaBean getters are required.
 *
 * <p>Each row represents one of the following:
 * <ul>
 *   <li>A data row ({@link #isDataRow} = true) — one round with ≥ 1 team assignment</li>
 *   <li>A break separator ({@link #isBreak} = true) — intra-phase break or section break
 *       inserted between data rows to give context to the photographer</li>
 * </ul>
 *
 * <h2>jmustache strict-mode compatibility</h2>
 * <p>All String fields default to {@code ""} and all boolean fields default to {@code false}.
 * This prevents {@code MustacheException} on absent keys — same guarantee as {@link LaufzettelRow}.
 *
 * @see ActivityScheduleAssembler
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E08S09.story.md">Story E08S09</a>
 */
public class ActivityScheduleRow {

    // -------------------------------------------------------------------------
    // Data row (AC3 — round number, time window, team names)
    // -------------------------------------------------------------------------

    /** {@code true} if this row represents a competition round with assigned teams. */
    private boolean isDataRow = false;

    /** Round number (1-based). 0 for break rows. */
    private int roundNumber = 0;

    /**
     * Formatted time window (e.g., "10:00–10:15"). Empty string when
     * {@code plannedStartTime} is null (AC7).
     */
    private String timeWindow = "";

    /**
     * Comma-separated list of team display names assigned in this round.
     * Non-empty only when {@link #isDataRow} is true.
     */
    private String teamNames = "";

    // -------------------------------------------------------------------------
    // Break separator row (AC4)
    // -------------------------------------------------------------------------

    /** {@code true} if this row is a break separator (intra-phase or section break). */
    private boolean isBreak = false;

    /** Display label for break rows (e.g., "Mittagspause" or "Pause"). Empty string if not a break. */
    private String breakLabel = "";

    /** Break time window (e.g., "12:00–12:30"). Empty string if no start time or not a break. */
    private String breakTimeWindow = "";

    // -------------------------------------------------------------------------
    // Default constructor
    // -------------------------------------------------------------------------

    /** Default constructor — all fields at their default (empty string / false / 0) values. */
    public ActivityScheduleRow() {
    }

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    /**
     * Creates a data row for one round.
     *
     * @param roundNumber round number (1-based)
     * @param timeWindow  formatted time window, or {@code ""} if no start time
     * @param teamNames   comma-separated team display names assigned in this round
     * @return a data row
     */
    public static ActivityScheduleRow dataRow(int roundNumber, String timeWindow, String teamNames) {
        ActivityScheduleRow row = new ActivityScheduleRow();
        row.isDataRow = true;
        row.roundNumber = roundNumber;
        row.timeWindow = timeWindow != null ? timeWindow : "";
        row.teamNames = teamNames != null ? teamNames : "";
        return row;
    }

    /**
     * Creates a break separator row (AC4).
     *
     * @param label      display label (e.g., "Mittagspause" or "Pause")
     * @param timeWindow formatted time window of the break, or {@code ""}
     * @return a break separator row
     */
    public static ActivityScheduleRow breakRow(String label, String timeWindow) {
        ActivityScheduleRow row = new ActivityScheduleRow();
        row.isBreak = true;
        row.breakLabel = label != null ? label : "";
        row.breakTimeWindow = timeWindow != null ? timeWindow : "";
        return row;
    }

    // -------------------------------------------------------------------------
    // Accessors — required by jmustache
    // -------------------------------------------------------------------------

    public boolean isDataRow() { return isDataRow; }
    public int getRoundNumber() { return roundNumber; }
    public String getTimeWindow() { return timeWindow; }
    public String getTeamNames() { return teamNames; }

    public boolean isBreak() { return isBreak; }
    public String getBreakLabel() { return breakLabel; }
    public String getBreakTimeWindow() { return breakTimeWindow; }
}
