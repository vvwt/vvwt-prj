// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DeviceLimitConfig} (E21S06, AC-TDD-DeviceLimitConfig).
 *
 * <h2>RED state</h2>
 *
 * <p>This test was committed RED: {@link DeviceLimitConfig} at {@code
 * de.vvwt.tm.tournament.internal.DeviceLimitConfig} did not exist at commit time, causing a compile
 * error — satisfying the DEC-22 Iron Law.
 *
 * <h2>Coverage</h2>
 *
 * <ul>
 *   <li>Default cap value = 10
 *   <li>Cap can be set to a positive value
 *   <li>Negative cap value rejected (setter validation)
 *   <li>Zero cap value rejected
 * </ul>
 *
 * @see DeviceLimitConfig
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S06">E21S06 — Device aggregate reconstruction (D-9, inventory line 516)</a>
 */
@DisplayName("DeviceLimitConfig — E21S06 AC-TDD-DeviceLimitConfig")
class DeviceLimitConfigTest {

    @Test
    @DisplayName("default cap is 10")
    void defaultCapIsTen() {
        DeviceLimitConfig config = new DeviceLimitConfig();
        assertThat(config.getMaxDeviceCount())
                .as("default device cap must be 10 per D-9")
                .isEqualTo(10);
    }

    @Test
    @DisplayName("cap can be set to a positive value")
    void capCanBeSetToPositiveValue() {
        DeviceLimitConfig config = new DeviceLimitConfig();
        config.setMaxDeviceCount(25);
        assertThat(config.getMaxDeviceCount()).isEqualTo(25);
    }

    @Test
    @DisplayName("negative cap is rejected with IllegalArgumentException")
    void negativeCapRejected() {
        DeviceLimitConfig config = new DeviceLimitConfig();
        assertThatThrownBy(() -> config.setMaxDeviceCount(-1))
                .as("negative cap must be rejected")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("-1");
    }

    @Test
    @DisplayName("zero cap is rejected with IllegalArgumentException")
    void zeroCapRejected() {
        DeviceLimitConfig config = new DeviceLimitConfig();
        assertThatThrownBy(() -> config.setMaxDeviceCount(0))
                .as("zero cap must be rejected")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
