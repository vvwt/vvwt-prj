// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.exceptions;

/**
 * Thrown when a SCORING_TABLET assignment is attempted but the device's PIN fail-counter has
 * reached N=10 (E49S01 AC6).
 *
 * <p>Results in HTTP 423 Locked. The device must be manually unlocked by an admin via {@code POST
 * /api/devices/{id}/pin-lock/reset} before another assignment attempt is accepted.
 *
 * @see <a href="E49S01">E49S01 — AC6: device-pin-locked, 423 Locked</a>
 */
public class DevicePinLockedException extends RuntimeException {

    /**
     * Constructs a {@code DevicePinLockedException}.
     *
     * @param deviceId the locked device id (for logging; must not appear verbatim in HTTP response
     *     body per AC-SEC-EXCEPTION-NO-LEAK)
     */
    public DevicePinLockedException(java.util.UUID deviceId) {
        super("Device is PIN-locked: " + deviceId);
    }
}
