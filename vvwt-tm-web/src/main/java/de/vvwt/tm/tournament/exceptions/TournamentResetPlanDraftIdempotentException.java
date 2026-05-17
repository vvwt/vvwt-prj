// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.exceptions;

import java.util.UUID;

/**
 * Thrown when reset-plan is attempted on a DRAFT tournament (E48S13,
 * AC-IMPL-TYPED-EXCEPTIONS-AND-HANDLERS).
 *
 * <p>The tournament is already in DRAFT — no reset is needed. HTTP 409 Conflict via {@link
 * de.vvwt.tm.web.GlobalExceptionHandler} with messageKey {@code
 * error.tournament.resetPlan.draftIdempotent}.
 *
 * @see <a href="DEC-35">DEC-35 — exceptions in {@code tournament.exceptions} public package</a>
 * @see <a href="E48S13">E48S13 — Tournament Admin Escape Hatch</a>
 */
public class TournamentResetPlanDraftIdempotentException extends ConflictException {

    /**
     * Constructs the exception for the given tournament ID.
     *
     * @param id the tournament UUID
     */
    public TournamentResetPlanDraftIdempotentException(UUID id) {
        super(
                "Tournament '"
                        + id
                        + "' is already in DRAFT status — no Phasenplan reset is needed.");
    }
}
