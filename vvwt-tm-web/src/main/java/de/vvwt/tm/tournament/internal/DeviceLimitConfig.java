// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the total device limit per tenant (E21S06 AC-DEVICELIMIT-ENFORCEMENT).
 *
 * <p>Bound to the {@code vvwt.devices.tm} prefix to avoid clash with the legacy {@code
 * vvwt.devices} prefix used by {@link de.vvwt.tm.config.DeviceLimitConfig} (which tracks only
 * DISPLAY devices with HTTP 429). This bean enforces a total device cap (all device types) and
 * throws HTTP 409 {@link DeviceLimitExceededException} when the cap is exceeded.
 *
 * <h2>Default</h2>
 *
 * <p>Default cap is 10 devices per tenant. Override via {@code vvwt.devices.tm.max-device-count} in
 * application properties.
 *
 * @see DeviceService
 * @see DeviceLimitExceededException
 * @see <a href="E21S06">E21S06 — Device aggregate reconstruction (inventory line 420)</a>
 */
@Component("tmDeviceLimitConfig")
@ConfigurationProperties(prefix = "vvwt.devices.tm")
public class DeviceLimitConfig {

    /** Default total device cap per tenant (D-9). */
    private static final int DEFAULT_MAX_DEVICE_COUNT = 10;

    private int maxDeviceCount = DEFAULT_MAX_DEVICE_COUNT;

    /**
     * Returns the maximum number of devices (all types combined) allowed per tenant.
     *
     * @return the device cap (always &gt; 0)
     */
    public int getMaxDeviceCount() {
        return maxDeviceCount;
    }

    /**
     * Sets the maximum number of devices per tenant. Must be a positive integer.
     *
     * @param maxDeviceCount the device cap
     * @throws IllegalArgumentException if {@code maxDeviceCount} is zero or negative
     */
    public void setMaxDeviceCount(int maxDeviceCount) {
        if (maxDeviceCount <= 0) {
            throw new IllegalArgumentException(
                    "maxDeviceCount must be positive, got: " + maxDeviceCount);
        }
        this.maxDeviceCount = maxDeviceCount;
    }
}
