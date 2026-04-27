package de.vvwt.tm.web.internal.dto;

import java.util.List;
import java.util.UUID;

/**
 * Top-level response for the timer data endpoint (AC1 — E11S02 / E26S01).
 *
 * <p>Contains everything the timer device needs to render the schedule and play audio at the
 * correct times:
 *
 * <ul>
 *   <li>Tournament metadata (name, status)
 *   <li>Current position within the tournament (AC5)
 *   <li>Full schedule of rounds and breaks with optional wall-clock times (AC1, AC2)
 *   <li>Phase structure summary (AC5)
 *   <li>Audio file URLs per category (AC4)
 * </ul>
 *
 * <p>Canonical FQN: {@code de.vvwt.tm.web.internal.dto.TimerDataResponse} per DEC-40 Clause B.
 * Reconstruction of legacy {@code de.vvwt.tm.infrastructure.web.timer.dto.TimerDataResponse} via
 * D-7 Option γ (E26S03 full TDD rebuild; E26S01 introduces at canonical FQN for compilation).
 * Return type of {@link de.vvwt.tm.timer.TimerDataService#buildTimerData(UUID)} per
 * C-3 signature-preservation.
 *
 * @see TimerScheduleEntryResponse
 * @see TimerPhaseResponse
 * @see TimerAudioResponse
 * @see <a href="contexts/artefacts/stories/E26S01.story.md">Story E26S01</a>
 */
public class TimerDataResponse {

    // ── Tournament metadata ───────────────────────────────────────────────────

    /** Human-readable tournament name. */
    private String tournamentName;

    /** Tournament UUID — stable identifier used for audio URL construction (AC4). */
    private UUID tournamentId;

    /**
     * Tenant UUID — used by the timer SPA to build the WebSocket subscription topic (DEC-5).
     */
    private UUID tenantId;

    /**
     * Tournament lifecycle status: {@code "PLANNED"}, {@code "ACTIVE"}, or {@code "COMPLETED"}.
     */
    private String tournamentStatus;

    // ── Current position (AC5) ────────────────────────────────────────────────

    /** 1-based number of the currently active phase. {@code 0} if no phase is active. */
    private int currentPhaseNumber;

    /** Current lap number within the active phase. {@code 0} if no phase is active. */
    private int currentLapNumber;

    // ── Schedule flags ────────────────────────────────────────────────────────

    /**
     * {@code true} if the tournament has a {@code plannedStartTime} and schedule entries carry
     * wall-clock times.
     */
    private boolean hasStartTime;

    /**
     * {@code true} if no phases have been configured yet (AC7 — no phases → empty schedule).
     */
    private boolean emptySchedule;

    // ── Schedule content ──────────────────────────────────────────────────────

    /** Ordered list of schedule entries: rounds and breaks interleaved (AC1, AC2, AC3). */
    private List<TimerScheduleEntryResponse> schedule;

    /** Phase structure summary — one entry per phase, ordered by phase sequence number (AC5). */
    private List<TimerPhaseResponse> phases;

    // ── Audio configuration (AC4) ─────────────────────────────────────────────

    /** Audio file URLs per category. URLs are {@code null} when no file is uploaded. */
    private TimerAudioResponse audio;

    /** Default constructor for Jackson. */
    public TimerDataResponse() {}

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String getTournamentName() {
        return tournamentName;
    }

    public void setTournamentName(String tournamentName) {
        this.tournamentName = tournamentName;
    }

    public UUID getTournamentId() {
        return tournamentId;
    }

    public void setTournamentId(UUID tournamentId) {
        this.tournamentId = tournamentId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public String getTournamentStatus() {
        return tournamentStatus;
    }

    public void setTournamentStatus(String tournamentStatus) {
        this.tournamentStatus = tournamentStatus;
    }

    public int getCurrentPhaseNumber() {
        return currentPhaseNumber;
    }

    public void setCurrentPhaseNumber(int currentPhaseNumber) {
        this.currentPhaseNumber = currentPhaseNumber;
    }

    public int getCurrentLapNumber() {
        return currentLapNumber;
    }

    public void setCurrentLapNumber(int currentLapNumber) {
        this.currentLapNumber = currentLapNumber;
    }

    public boolean isHasStartTime() {
        return hasStartTime;
    }

    public void setHasStartTime(boolean hasStartTime) {
        this.hasStartTime = hasStartTime;
    }

    public boolean isEmptySchedule() {
        return emptySchedule;
    }

    public void setEmptySchedule(boolean emptySchedule) {
        this.emptySchedule = emptySchedule;
    }

    public List<TimerScheduleEntryResponse> getSchedule() {
        return schedule;
    }

    public void setSchedule(List<TimerScheduleEntryResponse> schedule) {
        this.schedule = schedule;
    }

    public List<TimerPhaseResponse> getPhases() {
        return phases;
    }

    public void setPhases(List<TimerPhaseResponse> phases) {
        this.phases = phases;
    }

    public TimerAudioResponse getAudio() {
        return audio;
    }

    public void setAudio(TimerAudioResponse audio) {
        this.audio = audio;
    }
}
