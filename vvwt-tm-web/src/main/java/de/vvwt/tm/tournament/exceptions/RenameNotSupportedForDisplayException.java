// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.exceptions;

/**
 * Thrown when a rename is attempted on a DISPLAY device via the tablet-rename endpoint (E49S01
 * AC11).
 *
 * <p>DISPLAY devices are renamed via {@code PUT /api/devices/{id}/configure}. The tablet-rename
 * endpoint is SCORING_TABLET-only. Results in HTTP 422 Unprocessable Entity.
 *
 * @see <a href="E49S01">E49S01 — AC11: rename SCORING_TABLET only</a>
 */
public class RenameNotSupportedForDisplayException extends RuntimeException {

    /** Constructs a {@code RenameNotSupportedForDisplayException} with a fixed message. */
    public RenameNotSupportedForDisplayException() {
        super("Rename via this endpoint is only supported for SCORING_TABLET devices");
    }
}
