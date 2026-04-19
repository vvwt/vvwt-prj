package de.vvwt.tm.domain.audio;

/**
 * The three audio event categories for a tournament's timer sound configuration.
 *
 * <p>Story E11S01 — AC1–AC4, AC6. Files are stored on disk as {@code
 * {dataDir}/audio/{tournamentId}/{category}.mp3} where {@code {category}} is the lowercase enum
 * name (e.g., {@code start.mp3}, {@code end.mp3}, {@code pause.mp3}).
 *
 * <ul>
 *   <li>{@link #START} — played when a round starts (timer begins counting down)
 *   <li>{@link #END} — played when the round countdown reaches zero
 *   <li>{@link #PAUSE} — played during a pause/break between rounds
 * </ul>
 *
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E11S01.story.md">Story
 *     E11S01</a>
 */
public enum AudioCategory {
    START,
    END,
    PAUSE;

    /**
     * Returns the filename component used in the filesystem path, e.g. {@code "start.mp3"}.
     *
     * @return lowercase name with {@code .mp3} extension
     */
    public String toFileName() {
        return name().toLowerCase() + ".mp3";
    }
}
