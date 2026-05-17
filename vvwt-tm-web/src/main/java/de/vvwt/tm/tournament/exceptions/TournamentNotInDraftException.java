// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.exceptions;

import java.util.UUID;

/**
 * Thrown by {@link de.vvwt.tm.tournament.DraftService#apply} when the target tournament is not in
 * {@code DRAFT} status (E48S22, AC-ERROR-HANDLING-NON-DRAFT-STATUS-TYPED-EXCEPTION).
 *
 * <p>{@code apply()} requires the tournament to be in {@code DRAFT} status. Attempting to apply on
 * a {@code PLANNED}, {@code ACTIVE}, {@code COMPLETED}, or {@code CANCELLED} tournament throws this
 * exception — mapped to HTTP 409 Conflict with messageKey {@code draft.error.notInDraftStatus}.
 *
 * <p>This exception replaces the now-deleted {@code DraftAlreadyAppliedException} (which guarded
 * against re-apply by checking whether phases existed). Under the new atomic-apply invariant,
 * "phases exist iff status=PLANNED" — so the DRAFT-status precondition check at step (b) of {@code
 * apply()} subsumes and replaces the phases-exist guard at the correct architectural level
 * (AC-ERROR-HANDLING-DRAFT-ALREADY-APPLIED-COLLAPSED, AC-IMPL-PHASES-EXIST-GUARD-REMOVED).
 *
 * <p>The {@code messageKey} field is accessible via {@link #getMessageKey()} for downstream
 * consumers (e.g., {@link de.vvwt.tm.web.GlobalExceptionHandler}).
 *
 * @see de.vvwt.tm.tournament.DraftService#apply
 * @see ConflictException
 * @see <a href="DEC-35">DEC-35 — exceptions in {@code tournament.exceptions} public package</a>
 * @see <a href="E48S22">E48S22 — Atomic apply() + markPlanned public-surface removal</a>
 */
public class TournamentNotInDraftException extends ConflictException {

    private static final String MESSAGE_KEY = "draft.error.notInDraftStatus";

    private final UUID tournamentId;
    private final String currentStatus;

    /**
     * Constructs the exception for a tournament that is not in {@code DRAFT} status.
     *
     * @param tournamentId the tournament UUID
     * @param currentStatus the tournament's actual current status (e.g., "PLANNED")
     */
    public TournamentNotInDraftException(UUID tournamentId, String currentStatus) {
        super(
                "Cannot apply draft for tournament '"
                        + tournamentId
                        + "': tournament is in status "
                        + currentStatus
                        + " (only DRAFT tournaments can have a draft applied).");
        this.tournamentId = tournamentId;
        this.currentStatus = currentStatus;
    }

    /**
     * @return the i18n message key for this exception ({@code draft.error.notInDraftStatus})
     */
    public String getMessageKey() {
        return MESSAGE_KEY;
    }

    /**
     * @return the tournament UUID that triggered this exception
     */
    public UUID getTournamentId() {
        return tournamentId;
    }

    /**
     * @return the tournament's actual status at the time of the failed apply attempt
     */
    public String getCurrentStatus() {
        return currentStatus;
    }
}
