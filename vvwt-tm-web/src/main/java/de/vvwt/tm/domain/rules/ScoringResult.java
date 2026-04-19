package de.vvwt.tm.domain.rules;

/**
 * Immutable result of a {@link ScoringRule} calculation.
 *
 * <p>Holds the points awarded to each team slot for a single match. Points are always non-negative
 * integers (guard is enforced by the rules, not the record itself — see AC14).
 *
 * @param team1Points points awarded to the team in slot 1
 * @param team2Points points awarded to the team in slot 2
 * @see ScoringRule
 */
public record ScoringResult(int team1Points, int team2Points) {

    /**
     * Compact canonical constructor — validates that neither point value is negative.
     *
     * @throws IllegalArgumentException if either value is negative (AC14 overflow guard)
     */
    public ScoringResult {
        if (team1Points < 0) {
            throw new IllegalArgumentException("team1Points must be >= 0, got: " + team1Points);
        }
        if (team2Points < 0) {
            throw new IllegalArgumentException("team2Points must be >= 0, got: " + team2Points);
        }
    }
}
