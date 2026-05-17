// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.display;

/**
 * Thrown when no active phase exists for the current tenant's tournament (E25S01,
 * AC-RED-FIRST-NO-ACTIVE-PHASE-EXCEPTION).
 *
 * <p>Caught by {@link de.vvwt.tm.web.GlobalExceptionHandler#handleNoActivePhase} and translated to
 * HTTP 404 with body {@code { "status": "NO_ACTIVE_PHASE" }}.
 *
 * <p>Authored Q-1a TDD fresh at canonical FQN {@code de.vvwt.tm.display.NoActivePhaseException} per
 * DEC-22 Iron Law + D-7 Coexistence Option γ (delete legacy, author fresh). Legacy {@code
 * infrastructure.display.NoActivePhaseException} deleted at AC-DELETE-LEGACY-FIRST.
 *
 * @see de.vvwt.tm.web.GlobalExceptionHandler
 * @see DEC-22
 * @see E25S01
 */
public class NoActivePhaseException extends RuntimeException {

    /** Creates the exception with the canonical no-active-phase message. */
    public NoActivePhaseException() {
        super("No active phase exists for the current tenant");
    }
}
