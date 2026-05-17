// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.exceptions;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a tournament lookup yields no row in the active tenant scope.
 *
 * <p>This is the public-surface replacement for the legacy package-private {@code
 * TournamentNotFoundException} — part of the E24 print-context reconstruction under DEC-22 Iron
 * Law. The legacy class was deleted at E24S07 atomic cutover. The public class is available to all
 * bounded contexts that include {@code "tournament::exceptions"} in their {@code
 * allowedDependencies}.
 *
 * <h2>Defense-in-depth (Brief R-3)</h2>
 *
 * <p>{@link ResponseStatus} maps this exception to HTTP 404 via Spring MVC's {@code
 * ResponseStatusExceptionResolver} when no {@code @ExceptionHandler} matches, ensuring a 404 is
 * returned even without explicit handler registration.
 *
 * <h2>Security (AC-SECURITY-EXCEPTION-MESSAGE-DISCLOSURE)</h2>
 *
 * <p>The message includes the requested tournament UUID only — no entity fields, SQL fragments, or
 * stack-trace text. Acceptable per {@code AC-SEC-NO-EXCEPTION-LEAK} pattern.
 *
 * @see de.vvwt.tm.tournament.exceptions.TournamentNotFoundExceptionTest
 * @since E24S01 — Brief D-6, R-3; DEC-40 Clause A
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class TournamentNotFoundException extends RuntimeException {

    public TournamentNotFoundException(UUID tournamentId) {
        super("Tournament not found: " + tournamentId);
    }
}
