package de.vvwt.tm.timer.audio;

import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;

/**
 * Public service port for the {@code timer.audio} sub-package (DEC-35 + option A 2026-04-27).
 *
 * <p>Defines the contract for tournament audio file storage operations. Canonical FQN:
 * {@code de.vvwt.tm.timer.audio.AudioStorageService} per DEC-21 module layout. The {@code
 * timer.audio} package is a public sub-package of the {@code timer} Modulith module (not a separate
 * Modulith module) per user decision option A (2026-04-27). Accessible to external consumers via
 * the {@code "timer"} {@code allowedDependencies} entry.
 *
 * <p>Full interface with upload/list/delete methods is E26S02 scope. This E26S01 authored version
 * exposes only the {@link #stream} method required by {@link
 * de.vvwt.tm.timer.internal.DefaultTimerDataService} for audio URL construction. E26S02 will extend
 * this interface with the remaining 3 methods.
 *
 * <p>All operations are scoped to a tournament belonging to the active tenant (DEC-5, DEC-17). No
 * database table is used — persistence is purely filesystem-based per DEC-15 + E11S01 AC6.
 *
 * @see de.vvwt.tm.timer.internal.DefaultTimerDataService
 * @see <a href="contexts/artefacts/stories/E26S01.story.md">Story E26S01</a>
 * @see <a href="contexts/artefacts/stories/E26S02.story.md">Story E26S02 (full audio sub-package)</a>
 */
public interface AudioStorageService {

    /**
     * Opens a read stream for the audio file of the given tournament and category.
     *
     * <p>Returns an empty optional when no file has been uploaded for the category. The caller is
     * responsible for closing the returned stream.
     *
     * @param tournamentId the tournament UUID (must belong to the active tenant)
     * @param category the audio category (START, END, PAUSE)
     * @return an optional containing the audio input stream if a file exists; empty otherwise
     * @throws java.util.NoSuchElementException if the tournament does not exist or is not in the
     *     active tenant
     */
    Optional<InputStream> stream(UUID tournamentId, AudioCategory category);
}
