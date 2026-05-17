// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.exceptions;

/**
 * Thrown when a match-correction request is rejected because the parent tournament is not in the
 * {@code ACTIVE} lifecycle status (E48S25, AC-GUARD-TOURNAMENT-STATUS).
 *
 * <p>Corrections are only permitted on tournaments with status {@code ACTIVE}. A non-active
 * tournament (e.g., {@code DRAFT}, {@code PLANNED}, {@code CANCELLED}, {@code COMPLETED}) rejects
 * correction with this exception → HTTP 409 Conflict via {@code GlobalExceptionHandler}.
 *
 * <h2>Security contract</h2>
 *
 * <p>Message MUST NOT contain credentials, session tokens, or raw SQL (AC-SEC-EXCEPTION-NO-LEAK).
 *
 * @see de.vvwt.tm.scoring.internal.DefaultMatchCorrectionService
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="E48S25">E48S25 — Operator Match Score Correction + Nacherfassung</a>
 */
public class TournamentNotActiveException extends RuntimeException {

    /**
     * Constructs a {@code TournamentNotActiveException} with a human-readable message.
     *
     * @param message human-readable description of the guard violation (must not contain
     *     credentials, SQL, or stack traces)
     */
    public TournamentNotActiveException(String message) {
        super(message);
    }
}
