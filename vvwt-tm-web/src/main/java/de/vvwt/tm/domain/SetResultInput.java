package de.vvwt.tm.domain;

import java.util.UUID;

/**
 * Input DTO for {@link CascadeRecomputeService#registerMatchResult(SetResultInput)} (E03S11, AC1).
 *
 * <p>Carries the score for a single set within a match, plus optional audit metadata.
 * Immutable record — all validation of set scores is performed by the {@code SetValidationRule}
 * inside the cascade service.
 *
 * @param matchId     UUID of the match being scored (NOT NULL)
 * @param setIndex    0-based index of the set within the match (0..maxSets-1)
 * @param team1Points points scored by team 1 in this set (must be &ge; 0)
 * @param team2Points points scored by team 2 in this set (must be &ge; 0)
 * @param actorId     identity of the actor submitting the result; {@code null} in LAN/no-login mode
 * @param reason      organiser-provided correction reason; {@code null} for first entries
 */
public record SetResultInput(
        UUID matchId,
        int setIndex,
        int team1Points,
        int team2Points,
        String actorId,
        String reason
) {

    /**
     * Compact canonical constructor — validates required fields.
     *
     * @throws NullPointerException     if {@code matchId} is {@code null}
     * @throws IllegalArgumentException if {@code team1Points} or {@code team2Points} is negative,
     *                                  or if {@code setIndex} is negative
     */
    public SetResultInput {
        if (matchId == null) {
            throw new NullPointerException("matchId must not be null");
        }
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
