// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.photo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for crop-related configuration fields added to {@link PhotoStorageConfig} (E71S01
 * AC3).
 *
 * <p>DEC-22 Q-1a: authored RED-first before the production fields were added to {@code
 * PhotoStorageConfig}. Tests verify:
 *
 * <ul>
 *   <li>Default aspect ratio width = 11
 *   <li>Default aspect ratio height = 5
 *   <li>Default max long edge = 2200
 *   <li>Fields are mutable via setters (Spring {@code @ConfigurationProperties} binding contract)
 * </ul>
 *
 * @see PhotoStorageConfig
 * @since E71S01
 */
@DisplayName("PhotoStorageConfig — crop fields (E71S01 AC3)")
class PhotoStorageConfigCropTest {

    @Test
    @DisplayName("default cropAspectRatioWidth is 11")
    void defaultCropAspectRatioWidthIs11() {
        PhotoStorageConfig config = new PhotoStorageConfig();
        assertThat(config.getCropAspectRatioWidth()).isEqualTo(11);
    }

    @Test
    @DisplayName("default cropAspectRatioHeight is 5")
    void defaultCropAspectRatioHeightIs5() {
        PhotoStorageConfig config = new PhotoStorageConfig();
        assertThat(config.getCropAspectRatioHeight()).isEqualTo(5);
    }

    @Test
    @DisplayName("default cropMaxLongEdge is 2200")
    void defaultCropMaxLongEdgeIs2200() {
        PhotoStorageConfig config = new PhotoStorageConfig();
        assertThat(config.getCropMaxLongEdge()).isEqualTo(2200);
    }

    @Test
    @DisplayName("cropAspectRatioWidth setter overrides default")
    void cropAspectRatioWidthSetterOverridesDefault() {
        PhotoStorageConfig config = new PhotoStorageConfig();
        config.setCropAspectRatioWidth(4);
        assertThat(config.getCropAspectRatioWidth()).isEqualTo(4);
    }

    @Test
    @DisplayName("cropAspectRatioHeight setter overrides default")
    void cropAspectRatioHeightSetterOverridesDefault() {
        PhotoStorageConfig config = new PhotoStorageConfig();
        config.setCropAspectRatioHeight(3);
        assertThat(config.getCropAspectRatioHeight()).isEqualTo(3);
    }

    @Test
    @DisplayName("cropMaxLongEdge setter overrides default")
    void cropMaxLongEdgeSetterOverridesDefault() {
        PhotoStorageConfig config = new PhotoStorageConfig();
        config.setCropMaxLongEdge(1600);
        assertThat(config.getCropMaxLongEdge()).isEqualTo(1600);
    }
}
