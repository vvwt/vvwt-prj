// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.exceptions;

/**
 * Thrown when a new device registration would exceed the configured device limit per tenant (E21S06
 * AC-DEVICELIMIT-ENFORCEMENT).
 *
 * <p>Mapped to HTTP 409 Conflict by {@link de.vvwt.tm.web.DeviceController}. Response body is a
 * {@code DeviceLimitErrorResponse} carrying {@code errorCode}, {@code configuredLimit}, and {@code
 * currentCount}.
 *
 * <p>Distinct from {@link DisplayDeviceLimitExceededException} which enforces the DISPLAY-device
 * cap and maps to HTTP 429 Too Many Requests.
 *
 * <p>Relocated from {@code de.vvwt.tm.tournament.internal} to {@code
 * de.vvwt.tm.tournament.exceptions} (DEC-35 retrofit, E33S03) — class body unchanged.
 *
 * @see DisplayDeviceLimitExceededException
 * @see de.vvwt.tm.tournament.internal.DefaultDeviceService
 * @see <a href="E21S06">E21S06 — Device aggregate reconstruction (inventory line 420)</a>
 * @see <a href="E33S03">E33S03 — DEC-35 exception relocation to tournament.exceptions</a>
 */
public class DeviceLimitExceededException extends RuntimeException {

    private final int configuredLimit;
    private final long currentCount;

    /**
     * Constructs the exception with limit details.
     *
     * @param configuredLimit the configured maximum device count
     * @param currentCount the current number of registered devices for the tenant
     */
    public DeviceLimitExceededException(int configuredLimit, long currentCount) {
        super(
                "Device limit exceeded: configured limit is "
                        + configuredLimit
                        + ", current count is "
                        + currentCount);
        this.configuredLimit = configuredLimit;
        this.currentCount = currentCount;
    }

    /**
     * Returns the configured maximum device count.
     *
     * @return the device cap
     */
    public int getConfiguredLimit() {
        return configuredLimit;
    }

    /**
     * Returns the current number of registered devices for the tenant at the time of the exception.
     *
     * @return current device count
     */
    public long getCurrentCount() {
        return currentCount;
    }
}
