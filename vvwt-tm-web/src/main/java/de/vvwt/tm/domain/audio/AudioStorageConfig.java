package de.vvwt.tm.domain.audio;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for audio file storage (E11S01).
 *
 * <p>Bound to the {@code tm.audio} property namespace in {@code application.yml}.
 *
 * <p>DEC-15: the data directory must be outside the jlink archive (read-only at runtime).
 * Audio files are stored in a user-writable location on the host filesystem.
 *
 * <p>Example override:
 * <pre>
 * tm:
 *   audio:
 *     data-dir: /var/tournament-manager/audio
 * </pre>
 *
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E11S01.story.md">Story E11S01</a>
 */
@Component
@ConfigurationProperties(prefix = "tm.audio")
public class AudioStorageConfig {

    /**
     * Root directory for audio file storage. Subdirectories are created automatically
     * per tournament: {@code {dataDir}/{tournamentId}/{category}.mp3}.
     *
     * <p>Default: {@code ${user.home}/.tournament-manager/audio}.
     * Override via {@code TM_AUDIO_DATA_DIR} env var or {@code -Dtm.audio.data-dir}.
     */
    private String dataDir;

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }
}
