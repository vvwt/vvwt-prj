package de.vvwt.tm.tournament;

import java.util.OptionalInt;

/**
 * Defines the set-structure format of a match (DEC-21, DEC-22, E21S05).
 *
 * <p>Reconstruction-in-place counterpart of {@code de.vvwt.tm.domain.MatchFormat} (READ ONLY
 * reference; not imported). Enum value names are preserved character-for-character for DB/wire
 * compatibility (AC-ENUM-VALUES-STABLE). Each constant carries four immutable fields:
 *
 * <ul>
 *   <li>{@code maxSets} — maximum number of sets that can be played
 *   <li>{@code requiredToWin} — sets a team must win to claim the match
 *   <li>{@code decidingSet} — the index of the deciding set, or empty for {@code FIXED_2_SETS}
 *   <li>{@code allowsTies} — whether the format can produce a draw result
 * </ul>
 *
 * <p>Boundary-API (public surface of the {@code tournament} Modulith context) per inventory line
 * 174 — REST-exposed + slotopt consumer.
 *
 * <p>Persistence: stored as {@code VARCHAR} enum name in the {@code tournament.match_format}
 * column. Use {@link #fromPersistedName(String)} to fail fast on unknown DB values.
 *
 * @see MatchState
 * @see <a href="DEC-21">DEC-21 — Spring Modulith boundary-API placement</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S05">E21S05 — inventory line 174</a>
 */
public enum MatchFormat {

    /**
     * Single-set match — first set decides. No tie-break concept (every set has a winner; there is
     * no "gone to the deciding set" contrast).
     */
    BEST_OF_1(1, 1, 1, false),

    /** First to win 2 sets wins; maximum 3 sets. */
    BEST_OF_3(3, 2, 3, false),

    /** First to win 3 sets wins; maximum 5 sets. */
    BEST_OF_5(5, 3, 5, false),

    /** First to win 4 sets wins; maximum 7 sets. */
    BEST_OF_7(7, 4, 7, false),

    /**
     * Exactly 2 sets are always played, regardless of individual set outcomes. No deciding set;
     * ties (1-1) are allowed and produce {@link MatchState#FINISHED_STANDOFF}.
     */
    FIXED_2_SETS(2, 2, null, true);

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    private final int maxSets;
    private final int requiredToWin;

    /** Null for FIXED_2_SETS — that format has no deciding set. */
    private final Integer decidingSetValue;

    private final boolean allowsTies;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    MatchFormat(int maxSets, int requiredToWin, Integer decidingSet, boolean allowsTies) {
        this.maxSets = maxSets;
        this.requiredToWin = requiredToWin;
        this.decidingSetValue = decidingSet;
        this.allowsTies = allowsTies;
    }

    // -----------------------------------------------------------------------
    // Field accessors
    // -----------------------------------------------------------------------

    /**
     * Returns the maximum number of sets that can be played in this format.
     *
     * @return maximum set count (&ge; 1)
     */
    public int getMaxSets() {
        return maxSets;
    }

    /**
     * Returns the number of sets a team must win to claim the match.
     *
     * @return required-to-win count (&ge; 1)
     */
    public int getRequiredToWin() {
        return requiredToWin;
    }

    /**
     * Returns the index of the deciding set, if applicable.
     *
     * <p>Returns {@link OptionalInt#empty()} for {@link #FIXED_2_SETS} because that format has no
     * deciding-set concept — both sets are always played.
     *
     * @return deciding set index, or empty for tie-allowing formats
     */
    public OptionalInt getDecidingSet() {
        return decidingSetValue == null ? OptionalInt.empty() : OptionalInt.of(decidingSetValue);
    }

    /**
     * Returns whether this format permits a draw result.
     *
     * <p>Only {@link #FIXED_2_SETS} allows ties; all {@code BEST_OF_N} formats structurally prevent
     * ties because every set has a winner and play stops once one team reaches {@link
     * #getRequiredToWin()}.
     *
     * @return {@code true} if ties are possible; {@code false} otherwise
     */
    public boolean isAllowsTies() {
        return allowsTies;
    }

    // -----------------------------------------------------------------------
    // isTieBreak helper
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} if the completed match went to a deciding tie-break set.
     *
     * <p>A tie-break occurred when both teams were level at {@code requiredToWin - 1} sets each and
     * the match was decided in the final set.
     *
     * @param setsPlayed total number of sets completed (&ge; 0)
     * @param loserSetsWon sets won by the losing team (&ge; 0)
     * @return {@code true} if this was a deciding-set tie-break
     * @throws IllegalArgumentException if either argument is negative
     */
    public boolean isTieBreak(int setsPlayed, int loserSetsWon) {
        if (setsPlayed < 0) {
            throw new IllegalArgumentException("setsPlayed must be >= 0, got: " + setsPlayed);
        }
        if (loserSetsWon < 0) {
            throw new IllegalArgumentException("loserSetsWon must be >= 0, got: " + loserSetsWon);
        }
        if (allowsTies) {
            return false;
        }
        if (requiredToWin <= 1) {
            return false;
        }
        if (setsPlayed != maxSets) {
            return false;
        }
        return loserSetsWon == requiredToWin - 1;
    }

    // -----------------------------------------------------------------------
    // deriveMatchState helper
    // -----------------------------------------------------------------------

    /**
     * Derives a {@link MatchState} verdict from the current set scores.
     *
     * @param team1Sets sets won by team 1 (&ge; 0)
     * @param team2Sets sets won by team 2 (&ge; 0)
     * @param setsPlayed total sets completed (must equal {@code team1Sets + team2Sets})
     * @return the derived {@link MatchState} (never {@code null})
     * @throws IllegalArgumentException if any argument is negative or inconsistent
     */
    public MatchState deriveMatchState(int team1Sets, int team2Sets, int setsPlayed) {
        if (team1Sets < 0) {
            throw new IllegalArgumentException("team1Sets must be >= 0, got: " + team1Sets);
        }
        if (team2Sets < 0) {
            throw new IllegalArgumentException("team2Sets must be >= 0, got: " + team2Sets);
        }
        if (setsPlayed < 0) {
            throw new IllegalArgumentException("setsPlayed must be >= 0, got: " + setsPlayed);
        }
        if (team1Sets > maxSets) {
            throw new IllegalArgumentException(
                    "team1Sets ("
                            + team1Sets
                            + ") exceeds maxSets ("
                            + maxSets
                            + ") for format "
                            + this.name());
        }
        if (team2Sets > maxSets) {
            throw new IllegalArgumentException(
                    "team2Sets ("
                            + team2Sets
                            + ") exceeds maxSets ("
                            + maxSets
                            + ") for format "
                            + this.name());
        }
        if (setsPlayed != team1Sets + team2Sets) {
            throw new IllegalArgumentException(
                    "setsPlayed ("
                            + setsPlayed
                            + ") must equal team1Sets ("
                            + team1Sets
                            + ") + team2Sets ("
                            + team2Sets
                            + ")");
        }
        if (team1Sets == requiredToWin) {
            return MatchState.FINISHED_WINNER1;
        }
        if (team2Sets == requiredToWin) {
            return MatchState.FINISHED_WINNER2;
        }
        if (allowsTies && setsPlayed == maxSets && team1Sets == team2Sets) {
            return MatchState.FINISHED_STANDOFF;
        }
        return MatchState.ONCHECK;
    }

    // -----------------------------------------------------------------------
    // Persistence guard
    // -----------------------------------------------------------------------

    /**
     * Resolves a VARCHAR enum name read from the database back to a {@code MatchFormat} constant.
     *
     * <p>Fails fast with an {@link IllegalArgumentException} if the stored name is not a valid
     * constant — no silent fallback.
     *
     * @param name the enum name as stored in {@code tournament.match_format}
     * @return the matching {@code MatchFormat} constant
     * @throws IllegalArgumentException if {@code name} does not match any constant
     * @throws NullPointerException if {@code name} is {@code null}
     */
    public static MatchFormat fromPersistedName(String name) {
        if (name == null) {
            throw new NullPointerException("MatchFormat name must not be null");
        }
        try {
            return MatchFormat.valueOf(name);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Unknown MatchFormat value read from DB: '"
                            + name
                            + "'. Valid values: BEST_OF_1, BEST_OF_3, BEST_OF_5, BEST_OF_7,"
                            + " FIXED_2_SETS",
                    ex);
        }
    }
}
