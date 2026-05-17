// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.timer.audio;

/**
 * Audio category for tournament audio files.
 *
 * <p>Canonical FQN: {@code de.vvwt.tm.timer.audio.AudioCategory} per DEC-21 module layout
 * (sub-package of timer per option A — 2026-04-27 user decision). Three enum values preserved
 * verbatim per C-3 signature-preservation.
 *
 * <p>Full implementation is E26S02 scope. This class is authored at E26S01 as part of the {@code
 * timer.audio} public sub-package required for {@link AudioStorageService} compilation.
 *
 * @see AudioStorageService
 * @see <a href="contexts/artefacts/stories/E26S01.story.md">Story E26S01</a>
 * @see <a href="contexts/artefacts/stories/E26S02.story.md">Story E26S02 (full audio
 *     sub-package)</a>
 */
public enum AudioCategory {

    /** Audio played at the start of a tournament phase or event. */
    START,

    /** Audio played at the end of a tournament phase or event. */
    END,

    /** Audio played during breaks (pause). */
    PAUSE;

    /**
     * Returns the filename component used in the filesystem path, e.g. {@code "start.mp3"}.
     *
     * <p>File path pattern: {@code {dataDir}/{tournamentId}/{category}.mp3} per E11S01 AC6 /
     * DEC-15.
     *
     * @return lowercase enum name with {@code .mp3} extension
     */
    public String toFileName() {
        return name().toLowerCase() + ".mp3";
    }
}
