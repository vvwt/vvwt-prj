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
 * verbatim). Default {@code maxSizeBytes} = 25 MB preserved.
 *
 * <p>DEC-15: the data directory must be outside the jlink archive (read-only at runtime). Photo
 * files are stored in a user-writable location on the host filesystem.
 *
 * <p>E71S01 (AC3): adds three crop-configuration fields. All three are server-configured and
 * ENV-overridable via the {@code TM_PHOTOS_*} env-var pattern (DEC-68 Clause 3 escape-hatch).
 * Defaults: aspect ratio 11:5, max long edge 2200 px. The values are exposed to the frontend via
 * {@link de.vvwt.tm.web.SettingsController} (GET /api/settings), per AC3's mandate to use the
 * existing settings endpoint (DEC-78 reuse, NOT a new endpoint).
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
 *     crop-aspect-ratio-width: 4
 *     crop-aspect-ratio-height: 3
 *     crop-max-long-edge: 1600
 * </pre>
 *
 * <p>Or via environment variables:
 *
 * <pre>
 * TM_PHOTOS_CROP_ASPECT_RATIO_WIDTH=4
 * TM_PHOTOS_CROP_ASPECT_RATIO_HEIGHT=3
 * TM_PHOTOS_CROP_MAX_LONG_EDGE=1600
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
     * <p>Uploads exceeding this limit are rejected with {@link PhotoSizeException}. Default: 25 MB
     * (26214400 bytes) per E12S08 AC2/AC5 — raised from 5 MB to accommodate typical phone and
     * camera photos. Override via the {@code TM_PHOTOS_MAX_FILE_SIZE} environment variable (which
     * maps to the Spring property {@code tm.photos.max-size-bytes}) or via {@code
     * -Dtm.photos.max-size-bytes}.
     */
    private long maxSizeBytes = 25L * 1024 * 1024;

    /**
     * Width component of the target crop aspect ratio (E71S01 AC3).
     *
     * <p>Together with {@link #cropAspectRatioHeight} this defines the ratio W:H that the
     * in-browser crop step enforces. Default: 11 (ratio 11:5). Override via the {@code
     * TM_PHOTOS_CROP_ASPECT_RATIO_WIDTH} environment variable.
     */
    private int cropAspectRatioWidth = 11;

    /**
     * Height component of the target crop aspect ratio (E71S01 AC3).
     *
     * <p>Together with {@link #cropAspectRatioWidth} this defines the ratio W:H that the in-browser
     * crop step enforces. Default: 5 (ratio 11:5). Override via the {@code
     * TM_PHOTOS_CROP_ASPECT_RATIO_HEIGHT} environment variable.
     */
    private int cropAspectRatioHeight = 5;

    /**
     * Maximum long-edge pixel length after downscale (E71S01 AC3).
     *
     * <p>After cropping, the image is downscaled so its longest edge does not exceed this value.
     * Images already smaller than this limit are NOT upscaled. Default: 2200 px (proven print
     * quality at ~214 dpi at near-full A4-landscape width). Override via the {@code
     * TM_PHOTOS_CROP_MAX_LONG_EDGE} environment variable.
     */
    private int cropMaxLongEdge = 2200;

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

    public int getCropAspectRatioWidth() {
        return cropAspectRatioWidth;
    }

    public void setCropAspectRatioWidth(int cropAspectRatioWidth) {
        this.cropAspectRatioWidth = cropAspectRatioWidth;
    }

    public int getCropAspectRatioHeight() {
        return cropAspectRatioHeight;
    }

    public void setCropAspectRatioHeight(int cropAspectRatioHeight) {
        this.cropAspectRatioHeight = cropAspectRatioHeight;
    }

    public int getCropMaxLongEdge() {
        return cropMaxLongEdge;
    }

    public void setCropMaxLongEdge(int cropMaxLongEdge) {
        this.cropMaxLongEdge = cropMaxLongEdge;
    }
}
