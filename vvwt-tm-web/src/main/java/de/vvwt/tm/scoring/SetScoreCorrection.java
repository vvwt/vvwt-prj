// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.scoring;

/**
 * Immutable value object representing a single set's corrected score within a {@link
 * MatchCorrectionInput} batch (E48S25, AC-DOMAIN-SET-SCORE-CORRECTION).
 *
 * <p>Bounded-context-owned input element of the {@code scoring} module.
 *
 * @param setIndex 0-based index of the set within the match (&ge; 0)
 * @param team1Points corrected score for team 1 (&ge; 0)
 * @param team2Points corrected score for team 2 (&ge; 0)
 * @see MatchCorrectionInput
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="E48S25">E48S25 — Operator Match Score Correction + Nacherfassung</a>
 */
public record SetScoreCorrection(int setIndex, int team1Points, int team2Points) {

    /** Compact canonical constructor — validates non-negative fields. */
    public SetScoreCorrection {
        if (setIndex < 0) {
            throw new IllegalArgumentException("setIndex must be >= 0, got: " + setIndex);
        }
        if (team1Points < 0) {
            throw new IllegalArgumentException("team1Points must be >= 0, got: " + team1Points);
        }
        if (team2Points < 0) {
            throw new IllegalArgumentException("team2Points must be >= 0, got: " + team2Points);
        }
    }
}
