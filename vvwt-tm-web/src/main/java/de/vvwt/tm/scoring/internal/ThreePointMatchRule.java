// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.scoring.internal;

import de.vvwt.tm.scoring.ScoringResult;
import de.vvwt.tm.scoring.ScoringRule;
import de.vvwt.tm.tournament.MatchFormat;
import de.vvwt.tm.tournament.MatchOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Scoring rule: winner 3 / loser 0; if tie-break, winner 2 / loser 1; ties 1/1 (D-15).
 *
 * <p>Tie-break detection delegates to {@link MatchFormat#isTieBreak(int, int)} using {@code
 * (outcome.setCount, loserSetsWon)} — keeping tie-break semantics in one place across all consumers
 * (E03S06 AC3).
 *
 * <p>Tie semantics: ties are only valid when {@code format.isAllowsTies() == true}. A tie in a
 * {@code BEST_OF_N} format is a defensive {@link IllegalStateException} (upstream cascade bug, not
 * a recoverable error).
 *
 * <p>This rule is stateless and thread-safe. Spring registers it as a singleton bean named {@code
 * "threePoint"} — the value persisted in {@code Tournament.scoring_rule_id} per V2__e03_core_schema
 * (AC-BEAN-NAME-PRESERVED / E22S04).
 *
 * <p>Reconstructed from {@code de.vvwt.tm.domain.rules.ThreePointMatchRule} per DEC-22
 * Reconstruction-in-Place; legacy type remains in place during coexistence window ending at E22S11
 * cutover. Dual-bean-boot conflict resolved by the {@code @ComponentScan} on {@link
 * de.vvwt.tm.TournamentManagerApplication} exclusion (AC-COMPONENT-SCAN-EXCLUSION / E22S04).
 *
 * @see ScoringRule
 * @see ScoringResult
 */
@Component("threePoint")
class ThreePointMatchRule implements ScoringRule {

    private static final Logger log = LoggerFactory.getLogger(ThreePointMatchRule.class);

    @Override
    public ScoringResult calculatePoints(MatchOutcome outcome, MatchFormat format) {
        validateInputs(outcome, format);

        int team1Sets = outcome.getTeam1SetsWon();
        int team2Sets = outcome.getTeam2SetsWon();

        final int team1Points;
        final int team2Points;

        if (team1Sets == team2Sets) {
            // Tie case
            if (!format.isAllowsTies()) {
                throw new IllegalStateException(
                        "Impossible tie detected in format "
                                + format.name()
                                + " (allowsTies=false). Outcome: "
                                + outcome
                                + ". This indicates a bug in the cascade service — "
                                + "a BEST_OF_N match cannot end in a draw.");
            }
            // FIXED_2_SETS 1:1 → 1/1
            team1Points = 1;
            team2Points = 1;
        } else {
            // There is a winner
            boolean team1Won = team1Sets > team2Sets;
            int loserSetsWon = team1Won ? team2Sets : team1Sets;
            boolean isTieBreak = format.isTieBreak(outcome.getSetCount(), loserSetsWon);

            if (isTieBreak) {
                // Winner 2, loser 1
                team1Points = team1Won ? 2 : 1;
                team2Points = team1Won ? 1 : 2;
            } else {
                // Winner 3, loser 0
                team1Points = team1Won ? 3 : 0;
                team2Points = team1Won ? 0 : 3;
            }
        }

        ScoringResult result = new ScoringResult(team1Points, team2Points);

        log.debug(
                "threePoint: outcome={}, format={} → result=({}, {})",
                outcome,
                format,
                team1Points,
                team2Points);

        return result;
    }

    @Override
    public String getBeanId() {
        return "threePoint";
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private static void validateInputs(MatchOutcome outcome, MatchFormat format) {
        if (outcome == null) {
            throw new IllegalArgumentException("MatchOutcome must not be null");
        }
        if (format == null) {
            throw new IllegalArgumentException("MatchFormat must not be null");
        }
        if (outcome.getTeam1SetsWon() < 0) {
            throw new IllegalArgumentException(
                    "team1SetsWon must be >= 0, got: " + outcome.getTeam1SetsWon());
        }
        if (outcome.getTeam2SetsWon() < 0) {
            throw new IllegalArgumentException(
                    "team2SetsWon must be >= 0, got: " + outcome.getTeam2SetsWon());
        }
        if (outcome.getSetCount() < 0) {
            throw new IllegalArgumentException(
                    "setCount must be >= 0, got: " + outcome.getSetCount());
        }
        if (outcome.getSetCount() != outcome.getTeam1SetsWon() + outcome.getTeam2SetsWon()) {
            throw new IllegalArgumentException(
                    "setCount ("
                            + outcome.getSetCount()
                            + ") must equal team1SetsWon ("
                            + outcome.getTeam1SetsWon()
                            + ") + team2SetsWon ("
                            + outcome.getTeam2SetsWon()
                            + ")");
        }
    }
}
