package de.vvwt.tm.infrastructure.web.timer.dto;

/**
 * Audio file availability response for the timer data endpoint (E11S02 AC4).
 *
 * <p>Each URL field points to the audio streaming endpoint for the given category ({@code
 * /api/tournaments/{tournamentId}/audio/{category}/stream}). A {@code null} value means no file has
 * been uploaded for that category — the timer should not attempt to play audio for that event.
 *
 * @see <a
 *     href="../../../../../../../../../.gaai/project/contexts/artefacts/stories/E11S02.story.md">Story
 *     E11S02</a>
 */
public class TimerAudioResponse {

    /**
     * URL of the start-of-round audio file, or {@code null} if none uploaded. Points to {@code
     * /api/tournaments/{tournamentId}/audio/START/stream}.
     */
    private String startUrl;

    /**
     * URL of the end-of-round audio file, or {@code null} if none uploaded. Points to {@code
     * /api/tournaments/{tournamentId}/audio/END/stream}.
     */
    private String endUrl;

    /**
     * URL of the pause-music audio file, or {@code null} if none uploaded. Only played during
     * {@code REGULAR} breaks (D-8 / AC3). Points to {@code
     * /api/tournaments/{tournamentId}/audio/PAUSE/stream}.
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
