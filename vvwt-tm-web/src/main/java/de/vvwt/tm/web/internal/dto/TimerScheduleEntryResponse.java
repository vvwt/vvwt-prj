package de.vvwt.tm.web.internal.dto;

/**
 * A single entry in the timer schedule — either a match round or a break (AC1, AC3 — E11S02 / E26S01).
 *
 * <p>Uses a flat union structure with a {@code type} discriminator field:
 *
 * <ul>
 *   <li>{@code "ROUND"} — a match round. {@code phaseNumber} and {@code lapNumber} are set; {@code
 *       breakType} and {@code label} are {@code null}.
 *   <li>{@code "BREAK"} — a break between rounds. {@code breakType} is set ({@code "REGULAR"} or
 *       {@code "ADDITIONAL"}); {@code phaseNumber}, {@code lapNumber} are {@code null}.
 * </ul>
 *
 * <p>Canonical FQN: {@code de.vvwt.tm.web.internal.dto.TimerScheduleEntryResponse} per DEC-40
 * Clause B. Reconstruction of legacy {@code
 * de.vvwt.tm.infrastructure.web.timer.dto.TimerScheduleEntryResponse} via D-7 Option γ (E26S03
 * full TDD rebuild; E26S01 introduces at canonical FQN for compilation).
 *
 * @see TimerDataResponse
 * @see <a href="contexts/artefacts/stories/E26S01.story.md">Story E26S01</a>
 */
public class TimerScheduleEntryResponse {

    /** Entry discriminator. Values: {@code "ROUND"} or {@code "BREAK"}. */
    private String type;

    // ── ROUND fields ──────────────────────────────────────────────────────────

    /** 1-based phase number. Non-null for {@code ROUND} entries. */
    private Integer phaseNumber;

    /** 1-based lap number within the phase. Non-null for {@code ROUND} entries. */
    private Integer lapNumber;

    // ── BREAK fields ──────────────────────────────────────────────────────────

    /** Break type: {@code "REGULAR"} or {@code "ADDITIONAL"}. Non-null for {@code BREAK} entries. */
    private String breakType;

    /** Optional display label for the break (e.g., "Mittagspause"). May be {@code null}. */
    private String label;

    // ── Shared time fields ────────────────────────────────────────────────────

    /** Wall-clock start time in {@code "HH:mm"} format. {@code null} if no start time set. */
    private String startTime;

    /** Wall-clock end time in {@code "HH:mm"} format. {@code null} if no start time set. */
    private String endTime;

    /** Default constructor for Jackson. */
    public TimerScheduleEntryResponse() {}

    // ── Factory methods ───────────────────────────────────────────────────────

    /**
     * Creates a ROUND entry.
     *
     * @param phaseNumber 1-based phase number
     * @param lapNumber 1-based lap number
     * @param startTime wall-clock start time (HH:mm) or {@code null}
     * @param endTime wall-clock end time (HH:mm) or {@code null}
     * @return a ROUND schedule entry
     */
    public static TimerScheduleEntryResponse round(
            int phaseNumber, int lapNumber, String startTime, String endTime) {
        TimerScheduleEntryResponse entry = new TimerScheduleEntryResponse();
        entry.type = "ROUND";
        entry.phaseNumber = phaseNumber;
        entry.lapNumber = lapNumber;
        entry.startTime = startTime;
        entry.endTime = endTime;
        return entry;
    }

    /**
     * Creates a BREAK entry.
     *
     * @param breakType {@code "REGULAR"} or {@code "ADDITIONAL"}
     * @param label optional break label (may be {@code null})
     * @param startTime wall-clock start time (HH:mm) or {@code null}
     * @param endTime wall-clock end time (HH:mm) or {@code null}
     * @return a BREAK schedule entry
     */
    public static TimerScheduleEntryResponse breakEntry(
            String breakType, String label, String startTime, String endTime) {
        TimerScheduleEntryResponse entry = new TimerScheduleEntryResponse();
        entry.type = "BREAK";
        entry.breakType = breakType;
        entry.label = label;
        entry.startTime = startTime;
        entry.endTime = endTime;
        return entry;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Integer getPhaseNumber() {
        return phaseNumber;
    }

    public void setPhaseNumber(Integer phaseNumber) {
        this.phaseNumber = phaseNumber;
    }

    public Integer getLapNumber() {
        return lapNumber;
    }

    public void setLapNumber(Integer lapNumber) {
        this.lapNumber = lapNumber;
    }

    public String getBreakType() {
        return breakType;
    }

    public void setBreakType(String breakType) {
        this.breakType = breakType;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }
}
