package de.vvwt.tm.scoring;

/**
 * Immutable result of a {@link ScoringRule} calculation.
 *
 * <p>Holds the points awarded to each team slot for a single match. Points are always non-negative
 * integers (range invariant enforced in the compact constructor).
 *
 * <p>Reconstructed from {@code de.vvwt.tm.domain.rules.ScoringResult} per DEC-22
 * Reconstruction-in-Place; legacy type remains in place during coexistence window ending at E22S11
 * cutover.
 *
 * @param team1Points points awarded to the team in slot 1 (must be &ge; 0)
 * @param team2Points points awarded to the team in slot 2 (must be &ge; 0)
 * @see ScoringRule
 */
public record ScoringResult(int team1Points, int team2Points) {

    /**
     * Compact canonical constructor — validates that neither point value is negative.
     *
     * @throws IllegalArgumentException if either value is negative
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
