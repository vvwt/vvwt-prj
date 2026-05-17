// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.exceptions;

/**
 * Thrown when the PIN supplied during SCORING_TABLET assignment does not match the device's stored
 * PIN (E49S01 AC5).
 *
 * <p>Results in HTTP 403 Forbidden. The fail-counter is incremented before this exception is
 * thrown. At N=10 failures the device is locked and {@link DevicePinLockedException} is thrown
 * instead.
 *
 * @see <a href="E49S01">E49S01 — AC5: PIN mismatch → 403; AC6: fail-counter</a>
 */
public class PinMismatchException extends RuntimeException {

    /** Constructs a {@code PinMismatchException} with a fixed message. */
    public PinMismatchException() {
        super("PIN does not match");
    }
}
