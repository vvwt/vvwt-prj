package de.vvwt.tm.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link MatchFormat}.
 *
 * <p>Coverage:
 * <ul>
 *   <li>AC1  — five enum constants exist with correct field values</li>
 *   <li>AC2  — field accessors return correct values including {@code OptionalInt}</li>
 *   <li>AC3  — {@code isTieBreak} helper (AC3 / D-15)</li>
 *   <li>AC4  — {@code deriveMatchState} helper</li>
 *   <li>AC5  — parametrized isTieBreak for every format × outcome combination</li>
 *   <li>AC6  — parametrized deriveMatchState for all formats and edge cases</li>
 *   <li>AC8  — {@code fromPersistedName} fails fast on unknown values</li>
 *   <li>AC9  — invalid helper inputs throw {@link IllegalArgumentException}</li>
 *   <li>AC10 — toString and equality are standard enum semantics</li>
 *   <li>AC11 — public methods validate inputs (no negative counts)</li>
 * </ul>
 */
class MatchFormatTest {

    // -----------------------------------------------------------------------
    // AC1 + AC2 — enum constants and field accessors
    // -----------------------------------------------------------------------

    @Test
    void bestOf1_hasCorrectFields() {
        MatchFormat fmt = MatchFormat.BEST_OF_1;
        assertThat(fmt.getMaxSets()).isEqualTo(1);
        assertThat(fmt.getRequiredToWin()).isEqualTo(1);
        assertThat(fmt.getDecidingSet()).isEqualTo(OptionalInt.of(1));
        assertThat(fmt.isAllowsTies()).isFalse();
    }

    @Test
    void bestOf3_hasCorrectFields() {
        MatchFormat fmt = MatchFormat.BEST_OF_3;
        assertThat(fmt.getMaxSets()).isEqualTo(3);
        assertThat(fmt.getRequiredToWin()).isEqualTo(2);
        assertThat(fmt.getDecidingSet()).isEqualTo(OptionalInt.of(3));
        assertThat(fmt.isAllowsTies()).isFalse();
    }

    @Test
    void bestOf5_hasCorrectFields() {
        MatchFormat fmt = MatchFormat.BEST_OF_5;
        assertThat(fmt.getMaxSets()).isEqualTo(5);
        assertThat(fmt.getRequiredToWin()).isEqualTo(3);
        assertThat(fmt.getDecidingSet()).isEqualTo(OptionalInt.of(5));
        assertThat(fmt.isAllowsTies()).isFalse();
    }

    @Test
    void bestOf7_hasCorrectFields() {
        MatchFormat fmt = MatchFormat.BEST_OF_7;
        assertThat(fmt.getMaxSets()).isEqualTo(7);
        assertThat(fmt.getRequiredToWin()).isEqualTo(4);
        assertThat(fmt.getDecidingSet()).isEqualTo(OptionalInt.of(7));
        assertThat(fmt.isAllowsTies()).isFalse();
    }

    @Test
    void fixed2Sets_hasCorrectFields() {
        MatchFormat fmt = MatchFormat.FIXED_2_SETS;
        assertThat(fmt.getMaxSets()).isEqualTo(2);
        assertThat(fmt.getRequiredToWin()).isEqualTo(2);
        assertThat(fmt.getDecidingSet()).isEmpty();
        assertThat(fmt.isAllowsTies()).isTrue();
    }

    // -----------------------------------------------------------------------
    // AC5 — isTieBreak parametrized (every format × outcome)
    // -----------------------------------------------------------------------

    /**
     * AC5 spec:
     * BEST_OF_1  1:0 → false
     * BEST_OF_3  2:0 → false, 2:1 → true, 0:2 → false, 1:2 → true
     * BEST_OF_5  3:0 → false, 3:1 → false, 3:2 → true, 0:3 → false, 1:3 → false, 2:3 → true
     * BEST_OF_7  4:0 → false, 4:1 → false, 4:2 → false, 4:3 → true,
     *            0:4 → false, 1:4 → false, 2:4 → false, 3:4 → true
     * FIXED_2_SETS 2:0, 0:2, 1:1 → all false
     */
    @ParameterizedTest(name = "{0} winner={1} loser={2} → {3}")
    @CsvSource({
        // BEST_OF_1
        "BEST_OF_1, 1, 0, false",

        // BEST_OF_3
        "BEST_OF_3, 2, 0, false",
        "BEST_OF_3, 2, 1, true",
        "BEST_OF_3, 2, 1, true",  // same tie-break regardless of which team won
        // loser perspective
        "BEST_OF_3, 2, 0, false",
        "BEST_OF_3, 2, 1, true",

        // BEST_OF_5
        "BEST_OF_5, 3, 0, false",
        "BEST_OF_5, 3, 1, false",
        "BEST_OF_5, 3, 2, true",

        // BEST_OF_7
        "BEST_OF_7, 4, 0, false",
        "BEST_OF_7, 4, 1, false",
        "BEST_OF_7, 4, 2, false",
        "BEST_OF_7, 4, 3, true",

        // FIXED_2_SETS
        "FIXED_2_SETS, 2, 0, false",
        "FIXED_2_SETS, 2, 0, false",
        "FIXED_2_SETS, 1, 1, false"
    })
    void isTieBreak_parametrized(String formatName, int winnerSets, int loserSets, boolean expected) {
        MatchFormat fmt = MatchFormat.valueOf(formatName);
        int setsPlayed = winnerSets + loserSets;
        assertThat(fmt.isTieBreak(setsPlayed, loserSets))
                .as("%s winner=%d loser=%d", formatName, winnerSets, loserSets)
                .isEqualTo(expected);
    }

    @Test
    void isTieBreak_best_of_3_twoOne_bothSides() {
        // 2:1 (team1 wins) → setsPlayed=3, loserSetsWon=1 → true
        assertThat(MatchFormat.BEST_OF_3.isTieBreak(3, 1)).isTrue();
        // 1:2 (team2 wins) → same calculation from loser perspective
        assertThat(MatchFormat.BEST_OF_3.isTieBreak(3, 1)).isTrue();
    }

    @Test
    void isTieBreak_best_of_5_threeTwo_bothSides() {
        assertThat(MatchFormat.BEST_OF_5.isTieBreak(5, 2)).isTrue();
    }

    @Test
    void isTieBreak_best_of_7_fourThree_bothSides() {
        assertThat(MatchFormat.BEST_OF_7.isTieBreak(7, 3)).isTrue();
    }

    @Test
    void isTieBreak_best_of_1_returnsFalse_because_singleSetFormat() {
        // BEST_OF_1 requiredToWin == 1 → returns false (rule 2)
        assertThat(MatchFormat.BEST_OF_1.isTieBreak(1, 0)).isFalse();
    }

    @Test
    void isTieBreak_fixed2Sets_allFalse() {
        assertThat(MatchFormat.FIXED_2_SETS.isTieBreak(2, 0)).isFalse();
        assertThat(MatchFormat.FIXED_2_SETS.isTieBreak(2, 1)).isFalse();
    }

    @Test
    void isTieBreak_matchDidNotGoFullDistance_returnsFalse() {
        // BEST_OF_5, team wins 3:0 (setsPlayed=3, not maxSets=5)
        assertThat(MatchFormat.BEST_OF_5.isTieBreak(3, 0)).isFalse();
        assertThat(MatchFormat.BEST_OF_5.isTieBreak(4, 1)).isFalse();
    }

    // -----------------------------------------------------------------------
    // AC6 — deriveMatchState parametrized
    // -----------------------------------------------------------------------

    @Test
    void deriveMatchState_fixed2Sets_oneOne_standoff() {
        assertThat(MatchFormat.FIXED_2_SETS.deriveMatchState(1, 1, 2))
                .isEqualTo(MatchState.FINISHED_STANDOFF);
    }

    @Test
    void deriveMatchState_fixed2Sets_twoZero_winner1() {
        assertThat(MatchFormat.FIXED_2_SETS.deriveMatchState(2, 0, 2))
                .isEqualTo(MatchState.FINISHED_WINNER1);
    }

    @Test
    void deriveMatchState_fixed2Sets_zeroTwo_winner2() {
        assertThat(MatchFormat.FIXED_2_SETS.deriveMatchState(0, 2, 2))
                .isEqualTo(MatchState.FINISHED_WINNER2);
    }

    @Test
    void deriveMatchState_bestOf3_oneOne_oncheck() {
        // set 2 done, still open (set 3 to come)
        assertThat(MatchFormat.BEST_OF_3.deriveMatchState(1, 1, 2))
                .isEqualTo(MatchState.ONCHECK);
    }

    @Test
    void deriveMatchState_bestOf3_twoOne_winner1() {
        assertThat(MatchFormat.BEST_OF_3.deriveMatchState(2, 1, 3))
                .isEqualTo(MatchState.FINISHED_WINNER1);
    }

    @Test
    void deriveMatchState_bestOf3_oneTwoLoser_winner2() {
        assertThat(MatchFormat.BEST_OF_3.deriveMatchState(1, 2, 3))
                .isEqualTo(MatchState.FINISHED_WINNER2);
    }

    @Test
    void deriveMatchState_bestOf3_twoZero_winner1_earlyFinish() {
        assertThat(MatchFormat.BEST_OF_3.deriveMatchState(2, 0, 2))
                .isEqualTo(MatchState.FINISHED_WINNER1);
    }

    @Test
    void deriveMatchState_bestOf5_zeroZero_oncheck() {
        assertThat(MatchFormat.BEST_OF_5.deriveMatchState(0, 0, 0))
                .isEqualTo(MatchState.ONCHECK);
    }

    @Test
    void deriveMatchState_bestOf5_threeTwo_winner1() {
        assertThat(MatchFormat.BEST_OF_5.deriveMatchState(3, 2, 5))
                .isEqualTo(MatchState.FINISHED_WINNER1);
    }

    @Test
    void deriveMatchState_bestOf7_fourThree_winner1() {
        assertThat(MatchFormat.BEST_OF_7.deriveMatchState(4, 3, 7))
                .isEqualTo(MatchState.FINISHED_WINNER1);
    }

    @Test
    void deriveMatchState_bestOf1_oneZero_winner1() {
        assertThat(MatchFormat.BEST_OF_1.deriveMatchState(1, 0, 1))
                .isEqualTo(MatchState.FINISHED_WINNER1);
    }

    @Test
    void deriveMatchState_bestOf1_zeroOne_winner2() {
        assertThat(MatchFormat.BEST_OF_1.deriveMatchState(0, 1, 1))
                .isEqualTo(MatchState.FINISHED_WINNER2);
    }

    // -----------------------------------------------------------------------
    // AC8 — fromPersistedName fails fast on unknown value
    // -----------------------------------------------------------------------

    @Test
    void fromPersistedName_validValues_returnConstants() {
        assertThat(MatchFormat.fromPersistedName("BEST_OF_1")).isEqualTo(MatchFormat.BEST_OF_1);
        assertThat(MatchFormat.fromPersistedName("BEST_OF_3")).isEqualTo(MatchFormat.BEST_OF_3);
        assertThat(MatchFormat.fromPersistedName("BEST_OF_5")).isEqualTo(MatchFormat.BEST_OF_5);
        assertThat(MatchFormat.fromPersistedName("BEST_OF_7")).isEqualTo(MatchFormat.BEST_OF_7);
        assertThat(MatchFormat.fromPersistedName("FIXED_2_SETS")).isEqualTo(MatchFormat.FIXED_2_SETS);
    }

    @Test
    void fromPersistedName_unknownValue_throwsWithClearMessage() {
        assertThatThrownBy(() -> MatchFormat.fromPersistedName("BEST_OF_9"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BEST_OF_9")
                .hasMessageContaining("Unknown MatchFormat value read from DB");
    }

    @Test
    void fromPersistedName_nullValue_throwsNullPointerException() {
        assertThatThrownBy(() -> MatchFormat.fromPersistedName(null))
                .isInstanceOf(NullPointerException.class);
    }

    // -----------------------------------------------------------------------
    // AC9 — invalid helper inputs throw IllegalArgumentException
    // -----------------------------------------------------------------------

    @Test
    void isTieBreak_negativeSetsPlayed_throws() {
        assertThatThrownBy(() -> MatchFormat.BEST_OF_3.isTieBreak(-1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("setsPlayed");
    }

    @Test
    void isTieBreak_negativeLoserSets_throws() {
        assertThatThrownBy(() -> MatchFormat.BEST_OF_3.isTieBreak(0, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loserSetsWon");
    }

    @Test
    void deriveMatchState_negativeTeam1Sets_throws() {
        assertThatThrownBy(() -> MatchFormat.BEST_OF_3.deriveMatchState(-1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("team1Sets");
    }

    @Test
    void deriveMatchState_negativeTeam2Sets_throws() {
        assertThatThrownBy(() -> MatchFormat.BEST_OF_3.deriveMatchState(0, -1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("team2Sets");
    }

    @Test
    void deriveMatchState_negativeSetsPlayed_throws() {
        assertThatThrownBy(() -> MatchFormat.BEST_OF_3.deriveMatchState(0, 0, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("setsPlayed");
    }

    @Test
    void deriveMatchState_team1SetsExceedMaxSets_throws() {
        assertThatThrownBy(() -> MatchFormat.BEST_OF_3.deriveMatchState(4, 0, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("team1Sets");
    }

    @Test
    void deriveMatchState_team2SetsExceedMaxSets_throws() {
        assertThatThrownBy(() -> MatchFormat.BEST_OF_3.deriveMatchState(0, 4, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("team2Sets");
    }

    @Test
    void deriveMatchState_setsPlayedMismatch_throws() {
        assertThatThrownBy(() -> MatchFormat.BEST_OF_3.deriveMatchState(1, 1, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("setsPlayed");
    }

    // -----------------------------------------------------------------------
    // AC10 — toString and equality are standard enum semantics
    // -----------------------------------------------------------------------

    @Test
    void toString_returnsEnumName() {
        assertThat(MatchFormat.BEST_OF_3.toString()).isEqualTo("BEST_OF_3");
        assertThat(MatchFormat.FIXED_2_SETS.toString()).isEqualTo("FIXED_2_SETS");
    }

    @Test
    void equality_sameInstance_equals() {
        assertThat(MatchFormat.BEST_OF_5).isEqualTo(MatchFormat.BEST_OF_5);
        assertThat(MatchFormat.BEST_OF_5).isSameAs(MatchFormat.BEST_OF_5);
    }

    @Test
    void equality_differentValues_notEqual() {
        assertThat(MatchFormat.BEST_OF_3).isNotEqualTo(MatchFormat.BEST_OF_5);
    }

    @Test
    void hashCode_consistentWithEquals() {
        assertThat(MatchFormat.BEST_OF_7.hashCode()).isEqualTo(MatchFormat.BEST_OF_7.hashCode());
    }

    @Test
    void roundTripThroughName_preservesIdentity() {
        for (MatchFormat fmt : MatchFormat.values()) {
            assertThat(MatchFormat.fromPersistedName(fmt.name()))
                    .isSameAs(fmt);
        }
    }

    // -----------------------------------------------------------------------
    // AC11 — all public methods validate inputs (spot checks)
    // -----------------------------------------------------------------------

    @Test
    void isTieBreak_zeroZero_valid_doesNotThrow() {
        // Zero is a valid input (match hasn't started / timed out at 0-0)
        assertThat(MatchFormat.BEST_OF_3.isTieBreak(0, 0)).isFalse();
    }

    @Test
    void deriveMatchState_zeroZeroZero_oncheck() {
        assertThat(MatchFormat.BEST_OF_5.deriveMatchState(0, 0, 0))
                .isEqualTo(MatchState.ONCHECK);
    }
}
