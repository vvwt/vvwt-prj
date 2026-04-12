package de.vvwt.tm.domain;

/**
 * Lifecycle states for a Match entity.
 *
 * <p>Ported from legacy {@code TournamentPlatform} state constants (D-25/D-17).
 * Integer codes are preserved for wire/DB compatibility with the legacy system.
 *
 * <p>{@code FINISHED_STANDOFF} is only structurally reachable when the tournament's
 * {@link MatchFormat} has {@code allowsTies=true} (i.e., {@code FIXED_2_SETS}).
 * For all {@code BEST_OF_N} formats every set has a winner and the match stops at
 * {@code requiredToWin}, so {@code FINISHED_STANDOFF} never fires.
 *
 * <p>State transitions are owned by the cascade service (E03S11).
 * The {@link MatchFormat#deriveMatchState} helper derives a terminal/ongoing verdict
 * from set scores but does <em>not</em> assign {@code INPROGRESS} — that is set
 * externally when play begins.
 */
public enum MatchState {

    /** Match created, slot coordinates not yet assigned by slot-optimization. */
    OPEN(0),

    /** Slot coordinates filled; match is ready to be played. */
    ENABLED(10),

    /** Match play has started; sets are being scored. */
    INPROGRESS(30),

    /**
     * All sets have been played; cascade service is computing the outcome.
     * Transitions to one of the {@code FINISHED_*} states.
     */
    ONCHECK(35),

    /** Team 1 won the match. */
    FINISHED_WINNER1(51),

    /** Team 2 won the match. */
    FINISHED_WINNER2(52),

    /**
     * Match ended in a draw ({@code FIXED_2_SETS} 1-1).
     * Only reachable when {@link MatchFormat#isAllowsTies()} is {@code true}.
     */
    FINISHED_STANDOFF(50),

    /** Match was cancelled by the organiser before completion. */
    CANCELED(-10);

    /** Legacy integer code stored in the {@code match.state} DB column. */
    private final int legacyCode;

    MatchState(int legacyCode) {
        this.legacyCode = legacyCode;
    }

    /**
     * Returns the legacy integer code for DB persistence.
     *
     * @return the integer representation of this state
     */
    public int getLegacyCode() {
        return legacyCode;
    }

    /**
     * Resolves a legacy integer code back to a {@code MatchState} enum constant.
     *
     * @param code the integer code read from the database
     * @return the matching {@code MatchState}
     * @throws IllegalArgumentException if {@code code} does not correspond to any known state
     */
    public static MatchState fromLegacyCode(int code) {
        for (MatchState state : values()) {
            if (state.legacyCode == code) {
                return state;
            }
        }
        throw new IllegalArgumentException(
                "Unknown MatchState legacy code: " + code
                + ". Known codes: 0 (OPEN), 10 (ENABLED), 30 (INPROGRESS), 35 (ONCHECK), "
                + "50 (FINISHED_STANDOFF), 51 (FINISHED_WINNER1), 52 (FINISHED_WINNER2), -10 (CANCELED)");
    }
}
