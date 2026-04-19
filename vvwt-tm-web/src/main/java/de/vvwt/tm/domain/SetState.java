package de.vvwt.tm.domain;

/**
 * Legacy set-state enum for {@link SetResult}.
 *
 * <p>Ported from the legacy {@code MatchResult.setstate} column (O-9 in the Brief). The integer
 * codes are stored directly in the {@code set_result.set_state} column; a CHECK constraint at the
 * schema layer restricts valid values to this set.
 *
 * <h2>State meanings</h2>
 *
 * <ul>
 *   <li>{@link #OPEN} (0) — set has been created but not yet played
 *   <li>{@link #WINNER1} (1) — team 1 won this set
 *   <li>{@link #WINNER2} (2) — team 2 won this set
 *   <li>{@link #STANDOFF} (3) — set ended without a winner; structurally unreachable in V1 because
 *       no {@code SetValidationRule} implementation allows a set tie ({@code StandardVolleyballSet}
 *       requires a 2-point lead; {@code TimeBoundedSet} requires a 1-point lead). Retained for
 *       legacy parity and for potential future {@code SetValidationRule} implementations that would
 *       allow set ties.
 *   <li>{@link #CANCELED} (-1) — set was canceled (e.g., walk-over, organizer override)
 * </ul>
 *
 * @see SetResult
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E03S03.story.md">Story
 *     E03S03</a>
 */
public enum SetState {

    /** Set created but not yet played. Legacy code 0. */
    OPEN(0),

    /** Team 1 won this set. Legacy code 1. */
    WINNER1(1),

    /** Team 2 won this set. Legacy code 2. */
    WINNER2(2),

    /**
     * Set ended without a winner (tie). Structurally unreachable in V1 volleyball set validation
     * rules. Kept for legacy parity. Legacy code 3.
     */
    STANDOFF(3),

    /** Set was canceled. Legacy code -1. */
    CANCELED(-1);

    private final int legacyCode;

    SetState(int legacyCode) {
        this.legacyCode = legacyCode;
    }

    /**
     * Returns the integer code stored in the {@code set_result.set_state} column.
     *
     * @return the legacy integer code for this state
     */
    public int getLegacyCode() {
        return legacyCode;
    }

    /**
     * Resolves a legacy integer code to the corresponding {@link SetState} enum constant.
     *
     * @param code the integer code read from the database
     * @return the matching {@link SetState}
     * @throws IllegalArgumentException if {@code code} does not match any known state (guards
     *     against data corruption — the schema CHECK constraint should prevent invalid values from
     *     being stored, but application-layer code must also be robust)
     */
    public static SetState fromLegacyCode(int code) {
        for (SetState state : values()) {
            if (state.legacyCode == code) {
                return state;
            }
        }
        throw new IllegalArgumentException(
                "Unknown SetState legacy code: "
                        + code
                        + ". Valid codes: 0 (OPEN), 1 (WINNER1), 2 (WINNER2), 3 (STANDOFF), -1"
                        + " (CANCELED)");
    }
}
