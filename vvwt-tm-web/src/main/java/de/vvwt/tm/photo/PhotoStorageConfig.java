package de.vvwt.tm.photo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for team photo file storage (E12S02, E23S01).
 *
 * <p>Relocated from {@code de.vvwt.tm.domain.photo.PhotoStorageConfig} to the canonical {@code
 * de.vvwt.tm.photo} Modulith module per DEC-21, DEC-35 (config in public package), and E23S01. The
 * legacy package {@code de.vvwt.tm.domain.photo} remains on the classpath until E23S05 Cutover-1.
 *
 * <p>Bound to the {@code tm.photos} property namespace in {@code application.yml}. The property
 * namespace is unchanged by the relocation (AC-PACKAGE-INFO-CREATED: config class moves but
 * property namespace preserved verbatim).
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
 * @see PhotoStorageService
 * @since E12S02
 */
@Component("photoModuleStorageConfig")
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
