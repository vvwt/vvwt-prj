package de.vvwt.tm.tournament;

import java.util.UUID;

/**
 * Input DTO for scoring-context cascade operations (DEC-21, DEC-22, E21S05).
 *
 * <p>Reconstruction-in-place counterpart of {@code de.vvwt.tm.domain.SetResultInput} (READ ONLY
 * reference; not imported). Carries the score for a single set within a match, plus optional audit
 * metadata. Immutable record.
 *
 * <p>Boundary-API per inventory line 183 — consumed by {@code scoring/ScoreEntryService}.
 *
 * @param matchId UUID of the match being scored (NOT NULL)
 * @param setIndex 0-based index of the set within the match (0..maxSets-1)
 * @param team1Points points scored by team 1 in this set (must be &ge; 0)
 * @param team2Points points scored by team 2 in this set (must be &ge; 0)
 * @param actorId identity of the actor submitting the result; {@code null} in LAN/no-login mode
 * @param reason organiser-provided correction reason; {@code null} for first entries
 * @param sourceType audit source type: {@code "TABLET"}, {@code "ADMIN"}, or {@code null} for
 *     legacy callers
 * @param sourceDeviceId device UUID string when {@code sourceType} is {@code "TABLET"}; {@code
 *     null} otherwise
 * @see <a href="DEC-21">DEC-21 — boundary-API placement</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S05">E21S05 — inventory line 183</a>
 */
public record SetResultInput(
        UUID matchId,
        int setIndex,
        int team1Points,
        int team2Points,
        String actorId,
        String reason,
        String sourceType,
        String sourceDeviceId) {

    /** Source type constant for scoring tablet entries (E06S06, AC8). */
    public static final String SOURCE_TYPE_TABLET = "TABLET";

    /** Source type constant for admin UI corrections (E05S11, AC8). */
    public static final String SOURCE_TYPE_ADMIN = "ADMIN";

    /**
     * Compact canonical constructor — validates required fields.
     *
     * @throws NullPointerException if {@code matchId} is {@code null}
     * @throws IllegalArgumentException if {@code team1Points} or {@code team2Points} is negative,
     *     or if {@code setIndex} is negative
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

    /**
     * Convenience factory for legacy callers that do not supply source fields.
     *
     * @param matchId UUID of the match being scored (NOT NULL)
     * @param setIndex 0-based index of the set within the match
     * @param team1Points points scored by team 1 (&ge; 0)
     * @param team2Points points scored by team 2 (&ge; 0)
     * @param actorId identity of the actor; {@code null} in LAN/no-login mode
     * @param reason correction reason; {@code null} for first entries
     * @return a {@code SetResultInput} with {@code sourceType} and {@code sourceDeviceId} both
     *     {@code null}
     */
    public static SetResultInput legacy(
            UUID matchId,
            int setIndex,
            int team1Points,
            int team2Points,
            String actorId,
            String reason) {
        return new SetResultInput(
                matchId, setIndex, team1Points, team2Points, actorId, reason, null, null);
    }
}
