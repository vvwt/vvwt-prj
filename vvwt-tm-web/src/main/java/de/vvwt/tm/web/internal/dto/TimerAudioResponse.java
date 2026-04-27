package de.vvwt.tm.web.internal.dto;

/**
 * Audio file availability response for the timer data endpoint (AC4 — E11S02 / E26S01).
 *
 * <p>Each URL field points to the Wave-2-aligned audio streaming endpoint for the given category
 * ({@code /api/audio/tournaments/{tournamentId}/{category}/stream}). A {@code null} value means no
 * file has been uploaded for that category.
 *
 * <p>Canonical FQN: {@code de.vvwt.tm.web.internal.dto.TimerAudioResponse} per DEC-40 Clause B.
 * Reconstruction of legacy {@code de.vvwt.tm.infrastructure.web.timer.dto.TimerAudioResponse} via
 * D-7 Option γ (E26S03 full TDD rebuild; E26S01 introduces at canonical FQN for compilation).
 *
 * @see TimerDataResponse
 * @see <a href="contexts/artefacts/stories/E26S01.story.md">Story E26S01</a>
 */
public class TimerAudioResponse {

    /** URL of the start-of-round audio file, or {@code null} if none uploaded. */
    private String startUrl;

    /** URL of the end-of-round audio file, or {@code null} if none uploaded. */
    private String endUrl;

    /**
     * URL of the pause-music audio file, or {@code null} if none uploaded. Only played during
     * {@code REGULAR} breaks.
     */
    private String pauseUrl;

    /** Default constructor for Jackson. */
    public TimerAudioResponse() {}

    public TimerAudioResponse(String startUrl, String endUrl, String pauseUrl) {
        this.startUrl = startUrl;
        this.endUrl = endUrl;
        this.pauseUrl = pauseUrl;
    }

    public String getStartUrl() {
        return startUrl;
    }

    public void setStartUrl(String startUrl) {
        this.startUrl = startUrl;
    }

    public String getEndUrl() {
        return endUrl;
    }

    public void setEndUrl(String endUrl) {
        this.endUrl = endUrl;
    }

    public String getPauseUrl() {
        return pauseUrl;
    }

    public void setPauseUrl(String pauseUrl) {
        this.pauseUrl = pauseUrl;
    }
}
