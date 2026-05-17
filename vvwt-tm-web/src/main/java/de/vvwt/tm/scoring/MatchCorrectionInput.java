// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.scoring;

import java.util.List;
import java.util.UUID;

/**
 * Immutable input record for {@link MatchCorrectionService#correctMatchSets(MatchCorrectionInput)}
 * (E48S25, AC-DOMAIN-MATCH-CORRECTION-INPUT).
 *
 * <p>Bounded-context-owned input contract of the {@code scoring} module. Carries all set-score
 * corrections for a single match in a single batch.
 *
 * @param matchId UUID of the match to correct (NOT NULL)
 * @param tournamentId UUID of the parent tournament; required for DEC-37 Clause B lock acquisition
 * @param phaseId UUID of the parent phase; required for guard check (phase must be ACTIVE)
 * @param sets list of set-score corrections (NOT NULL; must not be empty for non-CANCELED matches)
 * @param actorId identity of the admin actor performing the correction; may be {@code null} in
 *     no-auth mode
 * @param reason optional operator-provided reason for the correction; may be {@code null}
 * @see MatchCorrectionService
 * @see SetScoreCorrection
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="DEC-37">DEC-37 Clause B — lock-first contract</a>
 * @see <a href="E48S25">E48S25 — Operator Match Score Correction + Nacherfassung</a>
 */
public record MatchCorrectionInput(
        UUID matchId,
        UUID tournamentId,
        UUID phaseId,
        List<SetScoreCorrection> sets,
        String actorId,
        String reason) {

    /** Compact canonical constructor — validates required fields. */
    public MatchCorrectionInput {
        if (matchId == null) {
            throw new NullPointerException("matchId must not be null");
        }
        if (tournamentId == null) {
            throw new NullPointerException("tournamentId must not be null");
        }
        if (phaseId == null) {
            throw new NullPointerException("phaseId must not be null");
        }
        if (sets == null) {
            throw new NullPointerException("sets must not be null");
        }
    }
}
