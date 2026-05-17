// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.exceptions;

/**
 * Thrown when a match-correction request is rejected because the match is in a live-scoring state
 * ({@code INPROGRESS} or {@code ONCHECK}) that prohibits operator correction (E48S25,
 * AC-GUARD-MATCH-STATE).
 *
 * <p>The correction endpoint is reserved for post-submission correction. Live matches ({@code
 * INPROGRESS}, {@code ONCHECK}) must be resolved through the normal scoring flow first. Maps to
 * HTTP 409 Conflict via {@code GlobalExceptionHandler}.
 *
 * <h2>Security contract</h2>
 *
 * <p>Message MUST NOT contain credentials, session tokens, or raw SQL (AC-SEC-EXCEPTION-NO-LEAK).
 *
 * @see de.vvwt.tm.scoring.internal.DefaultMatchCorrectionService
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="E48S25">E48S25 — Operator Match Score Correction + Nacherfassung</a>
 */
public class MatchStateGuardException extends RuntimeException {

    /**
     * Constructs a {@code MatchStateGuardException} with a human-readable message.
     *
     * @param message human-readable description of the guard violation (must not contain
     *     credentials, SQL, or stack traces)
     */
    public MatchStateGuardException(String message) {
        super(message);
    }
}
