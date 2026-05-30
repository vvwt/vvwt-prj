// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.exceptions;

/**
 * Thrown when a match-correction submit is rejected because the submitted set scores would derive
 * to {@code MatchState.ONCHECK} — an incomplete, non-terminal result that would leave the match in
 * a structurally unrecoverable state (E48S28, AC-TEST-CORRECTION-REJECTS-EMPTY-SUBMIT-RED).
 *
 * <p>This exception is thrown <em>pre-write</em>: no database state is changed, no DEC-37
 * per-tournament lock is acquired, and no {@code MatchResultChangedEvent} is published when this
 * exception fires.
 *
 * <p>The guard fires for all {@code MatchFormat} constants whenever the submitted set-score
 * aggregate computes to {@code ONCHECK}: for every format, the empty submit {@code (0,0,0)} yields
 * ONCHECK, as do all partial-non-winning tuples (e.g., {@code BEST_OF_3}: {@code (0,0,0)}, {@code
 * (1,0,1)}, {@code (0,1,1)}, {@code (1,1,2)}). This guard closes the lock-in bug confirmed
 * in-the-wild on 2026-05-30.
 *
 * <h2>HTTP mapping</h2>
 *
 * <p>Maps to HTTP 422 Unprocessable Content via {@code GlobalExceptionHandler} (messageKey {@code
 * error.correction.incomplete-result}).
 *
 * <h2>Security contract</h2>
 *
 * <p>Message MUST NOT contain credentials, session tokens, raw SQL, or stack-trace fragments. Only
 * the operator-safe submitted set count is included (AC-SEC-NO-LEAK-IN-ERROR-BODY).
 *
 * @see de.vvwt.tm.scoring.internal.DefaultMatchCorrectionService
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="DEC-37">DEC-37 Clause B — lock must NOT be acquired for rejected submits</a>
 * @see <a href="E48S28">E48S28 — Bug-Triage: pre-write ONCHECK guard</a>
 */
public class IncompleteCorrectionException extends RuntimeException {

    private final int submittedSetCount;

    /**
     * Constructs an {@code IncompleteCorrectionException}.
     *
     * @param message human-readable description (must not contain credentials, SQL, or stack
     *     traces)
     * @param submittedSetCount the number of sets in the submitted correction (operator-safe
     *     context, may appear in the error response body)
     */
    public IncompleteCorrectionException(String message, int submittedSetCount) {
        super(message);
        this.submittedSetCount = submittedSetCount;
    }

    /**
     * Returns the number of set-score entries submitted in the rejected correction.
     *
     * <p>This value is operator-safe and may be included in the HTTP error response body.
     *
     * @return submitted set count (&ge; 0)
     */
    public int getSubmittedSetCount() {
        return submittedSetCount;
    }
}
