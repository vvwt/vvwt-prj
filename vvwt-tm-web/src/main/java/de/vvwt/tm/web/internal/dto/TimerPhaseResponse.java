package de.vvwt.tm.web.internal.dto;

/**
 * Phase summary response for the timer data endpoint (AC5 — E11S02 / E26S01).
 *
 * <p>Provides structural information about each phase so the timer UI can render its current
 * position within the tournament.
 *
 * <p>Canonical FQN: {@code de.vvwt.tm.web.internal.dto.TimerPhaseResponse} per DEC-40 Clause B.
 * Reconstruction of legacy {@code de.vvwt.tm.infrastructure.web.timer.dto.TimerPhaseResponse} via
 * D-7 Option γ (E26S03 full TDD rebuild; E26S01 introduces at canonical FQN for compilation).
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
