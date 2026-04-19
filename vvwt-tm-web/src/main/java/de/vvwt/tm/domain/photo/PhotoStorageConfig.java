package de.vvwt.tm.domain.photo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for team photo file storage (E12S02).
 *
 * <p>Bound to the {@code tm.photos} property namespace in {@code application.yml}.
 *
 * <p>DEC-15: the data directory must be outside the jlink archive (read-only at runtime). Photo
 * files are stored in a user-writable location on the host filesystem.
 *
 * <p>Example override:
 *
 * <pre>
 * tm:
 *   photos:
 *     data-dir: /var/tournament-manager/photos
 * </pre>
 *
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E12S02.story.md">Story
 *     E12S02</a>
 */
@Component
@ConfigurationProperties(prefix = "tm.photos")
public class PhotoStorageConfig {

    /**
     * Root directory for team photo storage. Subdirectories are created automatically per
     * tournament: {@code {dataDir}/{tournamentId}/{teamId}.{ext}}.
     *
     * <p>Default: {@code ${user.home}/.tournament-manager/photos}. Override via {@code
     * TM_PHOTOS_DATA_DIR} env var or {@code -Dtm.photos.data-dir}.
     */
    private String dataDir;

    /**
     * Maximum allowed photo upload size in bytes.
     *
     * <p>AC7: uploads exceeding this limit are rejected with HTTP 400. Default: 5 MB (5242880
     * bytes). Override via {@code -Dtm.photos.max-size-bytes}.
     */
    private long maxSizeBytes = 5L * 1024 * 1024;

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public long getMaxSizeBytes() {
        return maxSizeBytes;
    }

    public void setMaxSizeBytes(long maxSizeBytes) {
        this.maxSizeBytes = maxSizeBytes;
    }
}
