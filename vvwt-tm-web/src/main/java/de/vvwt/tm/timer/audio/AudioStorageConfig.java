// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.timer.audio;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for tournament audio file storage.
 *
 * <p>Canonical FQN: {@code de.vvwt.tm.timer.audio.AudioStorageConfig} per DEC-21 module layout
 * (public sub-package of the {@code timer} Modulith module, option A per user decision 2026-04-27).
 * Property keys preserved verbatim per C-8 wire-stability (same {@code tm.audio} namespace as the
 * legacy audio storage config at the former domain.audio package).
 *
 * <p>Bound to the {@code tm.audio} property namespace in {@code application.yml}.
 *
 * <p>DEC-15: the data directory must be outside the jlink archive (read-only at runtime). Audio
 * files are stored in a user-writable location on the host filesystem.
 *
 * <p>Example override:
 *
 * <pre>
 * tm:
 *   audio:
 *     data-dir: /var/tournament-manager/audio
 * </pre>
 *
 * @see DefaultAudioStorageService
 * @see <a href="contexts/artefacts/stories/E26S02.story.md">Story E26S02</a>
 */
@Component
@ConfigurationProperties(prefix = "tm.audio")
public class AudioStorageConfig {

    /**
     * Root directory for audio file storage. Subdirectories are created automatically per
     * tournament: {@code {dataDir}/{tournamentId}/{category}.mp3}.
     *
     * <p>Default: {@code ${user.home}/.tournament-manager/audio}. Override via {@code
     * TM_AUDIO_DATA_DIR} env var or {@code -Dtm.audio.data-dir}.
     */
    private String dataDir;

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }
}
