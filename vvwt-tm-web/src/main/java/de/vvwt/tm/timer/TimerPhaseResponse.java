package de.vvwt.tm.timer;

/**
 * Phase summary response for the timer data endpoint (AC5 — E11S02 / E26S01).
 *
 * <p>Provides structural information about each phase so the timer UI can render its current
 * position within the tournament.
 *
 * <p>Canonical FQN: {@code de.vvwt.tm.timer.TimerPhaseResponse} per DEC-40 §2026-04-27
 * Clarification Pattern A (AC-RED-FIRST-TIMER-PHASE-RESPONSE — projection == wire shape;
 * no Clause B (a)/(c)/(d) condition fires; Decision Rule §289-296). Reconstruction of legacy
 * {@code de.vvwt.tm.infrastructure.web.timer.dto.TimerPhaseResponse} via D-7 Option γ
 * (E26S01 authors at bounded-context module root per Pattern A).
 *
 * @see TimerDataResponse
 * @see <a href="contexts/artefacts/stories/E26S01.story.md">Story E26S01</a>
 */
public class TimerPhaseResponse {

    /** 1-based phase sequence number. */
    private int phaseNumber;

    /** Human-readable phase label (e.g., "Phase 1", "Vorrunde"). */
    private String description;

    /** Phase lifecycle status: PENDING, ACTIVE, or COMPLETED. */
    private String status;

    /** Number of laps (match rounds) in this phase. Derived from match data. */
    private int lapCount;

    /** Default constructor for Jackson. */
    public TimerPhaseResponse() {}

    public TimerPhaseResponse(int phaseNumber, String description, String status, int lapCount) {
        this.phaseNumber = phaseNumber;
        this.description = description;
        this.status = status;
        this.lapCount = lapCount;
    }

    public int getPhaseNumber() {
        return phaseNumber;
    }

    public void setPhaseNumber(int phaseNumber) {
        this.phaseNumber = phaseNumber;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getLapCount() {
        return lapCount;
    }

    public void setLapCount(int lapCount) {
        this.lapCount = lapCount;
    }
}
