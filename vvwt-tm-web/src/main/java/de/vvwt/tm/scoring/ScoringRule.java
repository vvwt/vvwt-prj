package de.vvwt.tm.scoring;

import de.vvwt.tm.tournament.MatchFormat;
import de.vvwt.tm.tournament.MatchOutcome;

/**
 * Strategy interface for computing team points from a completed match result.
 *
 * <p>Every V1 scoring rule is a stateless Spring bean registered with a unique bean ID. The cascade
 * service resolves the applicable rule via {@code Tournament.scoringRuleId} through the {@code
 * ScoringRuleRegistry} (E22S05), then calls this method to compute per-match points before updating
 * {@code TeamAvatarRating} aggregates.
 *
 * <p>V1 implementations (E22S04):
 *
 * <ul>
 *   <li>{@code setPoints} — 1 point per set won by each team
 *   <li>{@code twoPoint} — winner 2 / loser 0, ties 1/1
 *   <li>{@code threePoint} — winner 3 / loser 0, tie-break winner 2 / loser 1
 * </ul>
 *
 * <p>Post-V1 sports can add additional scoring schemes by implementing this interface and
 * registering the new bean — no schema changes required.
 *
 * <p>Reconstructed from {@code de.vvwt.tm.domain.rules.ScoringRule} per DEC-22
 * Reconstruction-in-Place; legacy type remains in place during coexistence window ending at E22S11
 * cutover.
 *
 * @see ScoringResult
 */
public interface ScoringRule {

    /**
     * Computes the points awarded to each team for a single match.
     *
     * @param outcome the aggregated match result (non-null; set counts must be &ge; 0 and
     *     consistent)
     * @param format the match format governing tie-break detection (non-null)
     * @return a {@link ScoringResult} with non-negative point values for each team slot
     * @throws IllegalArgumentException if {@code outcome} or {@code format} is null, if set counts
     *     are negative, if the outcome is inconsistent ({@code setCount != team1SetsWon +
     *     team2SetsWon}), or if the format structurally prohibits the outcome (e.g., tie in a
     *     {@code BEST_OF_N} format)
     */
    ScoringResult calculatePoints(MatchOutcome outcome, MatchFormat format);

    /**
     * Returns the Spring bean ID of this rule, used as the key in the {@code ScoringRuleRegistry}
     * (E22S05).
     *
     * <p>The returned value must match the {@code @Component} name declared on the implementation
     * class and the value stored in {@code Tournament.scoringRuleId}.
     *
     * @return non-null, non-empty Spring bean ID (e.g., {@code "setPoints"}, {@code "threePoint"})
     */
    String getBeanId();
}
