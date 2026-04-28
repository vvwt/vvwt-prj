package de.vvwt.tm.timer;

/**
 * Audio file availability response for the timer data endpoint (AC4 — E11S02 / E26S01).
 *
 * <p>Each URL field points to the Wave-2-aligned audio streaming endpoint for the given category
 * ({@code /api/audio/tournaments/{tournamentId}/{category}/stream}). A {@code null} value means no
 * file has been uploaded for that category.
 *
 * <p>Canonical FQN: {@code de.vvwt.tm.timer.TimerAudioResponse} per DEC-40 §2026-04-27
 * Clarification Pattern A (AC-RED-FIRST-TIMER-AUDIO-RESPONSE — projection == wire shape;
 * no Clause B (a)/(c)/(d) condition fires; Decision Rule §289-296). Reconstruction of legacy
 * {@code de.vvwt.tm.infrastructure.web.timer.dto.TimerAudioResponse} via D-7 Option γ
 * (E26S01 authors at bounded-context module root per Pattern A).
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
