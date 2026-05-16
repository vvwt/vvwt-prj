// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

/**
 * Set lifecycle states for {@link SetResult} (DEC-21, DEC-22, E21S05).
 *
 * <p>Reconstruction-in-place counterpart of {@code de.vvwt.tm.domain.SetState} (READ ONLY
 * reference; not imported). Integer codes are preserved character-for-character for DB
 * compatibility (AC-ENUM-VALUES-STABLE).
 *
 * <p>Boundary-API (public surface of the {@code tournament} Modulith context) per inventory line
 * 184 — refinement elevation for public-API consistency with {@link SetResult}, which IS
 * boundary-API. Inventory grep found 0 cross-context imports for {@code SetState} in legacy, but
 * placement here is a documented override (Brief D-4 consistency grounds).
 *
 * <p>{@code STANDOFF} (code 3) is structurally unreachable in V1 because no {@code
 * SetValidationRule} implementation allows a set tie. Retained for legacy parity and potential
 * future implementations.
 *
 * @see SetResult
 * @see <a href="DEC-21">DEC-21 — boundary-API placement</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S05">E21S05 — inventory line 184 (SetState inventory quirk note)</a>
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
     * @throws IllegalArgumentException if {@code code} does not match any known state
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
                        + ". Valid codes: 0 (OPEN), 1 (WINNER1), 2 (WINNER2), 3 (STANDOFF),"
                        + " -1 (CANCELED)");
    }
}
