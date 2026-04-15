package de.vvwt.tm.infrastructure.web.timer.dto;

import java.util.List;
import java.util.UUID;

/**
 * Top-level response for the timer data endpoint (E11S02 AC1).
 *
 * <p>Contains everything the timer device needs to render the schedule and play audio
 * at the correct times:
 * <ul>
 *   <li>Tournament metadata (name, status)</li>
 *   <li>Current position within the tournament (AC5)</li>
 *   <li>Full schedule of rounds and breaks with optional wall-clock times (AC1, AC2)</li>
 *   <li>Phase structure summary (AC5)</li>
 *   <li>Audio file URLs per category (AC4)</li>
 * </ul>
 *
 * <h2>Schedule structure (AC1, AC2, AC3)</h2>
 * <p>{@code schedule} is an ordered list of {@link TimerScheduleEntryResponse} covering every
 * match round and break. Entries alternate between {@code "ROUND"} and {@code "BREAK"} as
 * produced by {@link de.vvwt.tm.domain.timeline.TimelineCalculationService}. Break entries
 * carry a {@code breakType} of {@code "REGULAR"} or {@code "ADDITIONAL"} per AC3.
 *
 * <h2>Empty schedule (AC7)</h2>
 * <p>When no phases have been configured for the tournament, {@code emptySchedule} is {@code true}
 * and {@code schedule} is an empty list. This is a 200 response — the timer URL is valid but
 * the schedule is not yet available.
 *
 * <h2>Time fields (AC2)</h2>
 * <p>{@code hasStartTime} is {@code true} when the tournament has a {@code plannedStartTime}.
 * When {@code false}, all {@code startTime}/{@code endTime} fields in schedule entries are
 * {@code null}.
 *
 * @see TimerScheduleEntryResponse
 * @see TimerPhaseResponse
 * @see TimerAudioResponse
 * @see <a href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E11S02.story.md">Story E11S02</a>
 */
public class TimerDataResponse {

    // ── Tournament metadata ───────────────────────────────────────────────────

    /** Human-readable tournament name. */
    private String tournamentName;

    /** Tournament UUID — stable identifier used for audio URL construction (AC4). */
    private UUID tournamentId;

    /**
     * Tenant UUID — used by the timer SPA to build the WebSocket subscription topic
     * {@code /topic/display/{tenantId}/events} (E11S05 AC1, DEC-5).
     */
    private UUID tenantId;

    /**
     * Tournament lifecycle status: {@code "PLANNED"}, {@code "ACTIVE"}, or {@code "COMPLETED"}.
     * (AC5 — timer page uses this to render its current position)
     */
    private String tournamentStatus;

    // ── Current position (AC5) ────────────────────────────────────────────────

    /**
     * 1-based number of the currently active phase. {@code 0} if no phase is active.
     * (AC5 — timer renders current lap within current phase)
     */
    private int currentPhaseNumber;

    /**
     * Current lap number within the active phase. {@code 0} if no phase is active or no lap
     * has started. Equal to {@link de.vvwt.tm.domain.Phase#getCurrentLapNumber()}.
     * (AC5)
     */
    private int currentLapNumber;

    // ── Schedule flags ────────────────────────────────────────────────────────

    /**
     * {@code true} if the tournament has a {@code plannedStartTime} and schedule entries
     * carry wall-clock times. {@code false} means all time fields in schedule entries are
     * {@code null}. (AC2)
     */
    private boolean hasStartTime;

    /**
     * {@code true} if no phases have been configured yet (AC7 — no phases → empty schedule warning).
     * The timer URL is valid (tournament exists) but the schedule is unavailable.
     */
    private boolean emptySchedule;

    // ── Schedule content ──────────────────────────────────────────────────────

    /**
     * Ordered list of schedule entries: rounds and breaks interleaved (AC1, AC2, AC3).
     * Empty when {@code emptySchedule == true}.
     */
    private List<TimerScheduleEntryResponse> schedule;

    /**
     * Phase structure summary — one entry per phase, ordered by phase sequence number (AC5).
     */
    private List<TimerPhaseResponse> phases;

    // ── Audio configuration (AC4) ─────────────────────────────────────────────

    /**
     * Audio file URLs per category. URLs are {@code null} when no file is uploaded for
     * that category. (AC4)
     */
    private TimerAudioResponse audio;

    /** Default constructor for Jackson. */
    public TimerDataResponse() {
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String getTournamentName() { return tournamentName; }
    public void setTournamentName(String tournamentName) { this.tournamentName = tournamentName; }

    public UUID getTournamentId() { return tournamentId; }
    public void setTournamentId(UUID tournamentId) { this.tournamentId = tournamentId; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public String getTournamentStatus() { return tournamentStatus; }
    public void setTournamentStatus(String tournamentStatus) { this.tournamentStatus = tournamentStatus; }

    public int getCurrentPhaseNumber() { return currentPhaseNumber; }
    public void setCurrentPhaseNumber(int currentPhaseNumber) { this.currentPhaseNumber = currentPhaseNumber; }

    public int getCurrentLapNumber() { return currentLapNumber; }
    public void setCurrentLapNumber(int currentLapNumber) { this.currentLapNumber = currentLapNumber; }

    public boolean isHasStartTime() { return hasStartTime; }
    public void setHasStartTime(boolean hasStartTime) { this.hasStartTime = hasStartTime; }

    public boolean isEmptySchedule() { return emptySchedule; }
    public void setEmptySchedule(boolean emptySchedule) { this.emptySchedule = emptySchedule; }

    public List<TimerScheduleEntryResponse> getSchedule() { return schedule; }
    public void setSchedule(List<TimerScheduleEntryResponse> schedule) { this.schedule = schedule; }

    public List<TimerPhaseResponse> getPhases() { return phases; }
    public void setPhases(List<TimerPhaseResponse> phases) { this.phases = phases; }

    public TimerAudioResponse getAudio() { return audio; }
    public void setAudio(TimerAudioResponse audio) { this.audio = audio; }
}
