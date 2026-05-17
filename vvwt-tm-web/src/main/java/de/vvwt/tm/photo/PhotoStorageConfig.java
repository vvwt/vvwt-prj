// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.photo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for team photo file storage (E36S01 Q-1a TDD rebuild).
 *
 * <p>Rebuilt from deleted Q-1b artefact at the same canonical FQN ({@code de.vvwt.tm.photo}) per
 * Brief D-7 Option γ. Getter/setter signatures and property namespace preserved verbatim per
 * AC-CONFIG-BINDING-PRESERVED: namespace {@code tm.photos}, bean name {@code
 * "photoModuleStorageConfig"}, fields {@code dataDir} and {@code maxSizeBytes}.
 *
 * <p>Bound to the {@code tm.photos} property namespace in {@code application.yml}. The property
 * namespace is unchanged by the rebuild (AC-CONFIG-BINDING-PRESERVED: namespace preserved
 * verbatim). Default {@code maxSizeBytes} = 5 MB preserved.
 *
 * <p>DEC-15: the data directory must be outside the jlink archive (read-only at runtime). Photo
 * files are stored in a user-writable location on the host filesystem.
 *
 * <p>Historical provenance: originally E12S02; relocated to this module by E23S01 (Q-1b); rebuilt
 * Q-1a RED-first by E36S01 per DEC-22 Iron Law + DEC-41 §3 hierarchy clause (1).
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
 * @since E36S01
 */
@Component("photoModuleStorageConfig")
@ConfigurationProperties(prefix = "tm.photos")
public class PhotoStorageConfig {

    /**
     * Root directory for team photo storage. Subdirectories are created automatically per
     * tournament: {@code {dataDir}/{tournamentId}/{teamId}.{ext}}.
     *
     * <p>Default: {@code ${user.home}/.tournament-manager/photos}. Override via {@code
     * -Dtm.photos.data-dir}.
     */
    private String dataDir;

    /**
     * Maximum allowed photo upload size in bytes.
     *
     * <p>Uploads exceeding this limit are rejected with {@link PhotoSizeException}. Default: 5 MB
     * (5242880 bytes). Override via {@code -Dtm.photos.max-size-bytes}.
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
