package de.vvwt.tm.infrastructure.web.timer.dto;

/**
 * Phase summary response for the timer data endpoint (E11S02 AC5).
 *
 * <p>Provides structural information about each phase so the timer UI can render its
 * current position within the tournament.
 *
 * @see <a href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E11S02.story.md">Story E11S02</a>
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
    public TimerPhaseResponse() {
    }

    public TimerPhaseResponse(int phaseNumber, String description, String status, int lapCount) {
        this.phaseNumber = phaseNumber;
        this.description = description;
        this.status = status;
        this.lapCount = lapCount;
    }

    public int getPhaseNumber() { return phaseNumber; }
    public void setPhaseNumber(int phaseNumber) { this.phaseNumber = phaseNumber; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getLapCount() { return lapCount; }
    public void setLapCount(int lapCount) { this.lapCount = lapCount; }
}
