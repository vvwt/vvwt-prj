package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MatchFormat} enum (E21S05, AC-TDD-MatchFormat).
 *
 * <h2>RED state</h2>
 *
 * <p>This test was committed RED: {@link MatchFormat} at {@code de.vvwt.tm.tournament.MatchFormat}
 * did not exist at commit time, causing a compile error — satisfying the DEC-22 Iron Law.
 *
 * <h2>Coverage</h2>
 *
 * <ul>
 *   <li>Enum value names stable (AC-ENUM-VALUES-STABLE)
 *   <li>{@code isTieBreak} canonical tie-break detection
 *   <li>{@code deriveMatchState} verdict derivation
 *   <li>{@code fromPersistedName} fast-fail on unknown name
 * </ul>
 *
 * @see MatchFormat
 * @see <a href="DEC-21">DEC-21 — boundary-API placement</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S05">E21S05 — inventory line 174</a>
 */
@DisplayName("MatchFormat enum — E21S05 AC-TDD-MatchFormat")
class MatchFormatTest {

    @Test
    @DisplayName("All expected enum value names are present (AC-ENUM-VALUES-STABLE)")
    void enumValueNamesAreStable() {
        assertThat(MatchFormat.values())
                .extracting(Enum::name)
                .containsExactlyInAnyOrder(
                        "BEST_OF_1", "BEST_OF_3", "BEST_OF_5", "BEST_OF_7", "FIXED_2_SETS");
    }

    @Test
    @DisplayName("isTieBreak returns true for deciding-set scenario in BEST_OF_3")
    void isTieBreakTrueForBestOf3TieBreak() {
        // 3-set match, loser won 1 set — tie-break occurred
        assertThat(MatchFormat.BEST_OF_3.isTieBreak(3, 1)).isTrue();
    }

    @Test
    @DisplayName("isTieBreak returns false for FIXED_2_SETS (allowsTies)")
    void isTieBreakFalseForFixed2Sets() {
        assertThat(MatchFormat.FIXED_2_SETS.isTieBreak(2, 1)).isFalse();
    }

    @Test
    @DisplayName("isTieBreak returns false for non-deciding-distance match")
    void isTieBreakFalseForEarlyFinish() {
        // BEST_OF_3 finished in 2 sets — no tie-break
        assertThat(MatchFormat.BEST_OF_3.isTieBreak(2, 0)).isFalse();
    }

    @Test
    @DisplayName("deriveMatchState returns FINISHED_WINNER1 when team1 reaches requiredToWin")
    void deriveMatchStateTeam1Wins() {
        assertThat(MatchFormat.BEST_OF_3.deriveMatchState(2, 0, 2))
                .isEqualTo(MatchState.FINISHED_WINNER1);
    }

    @Test
    @DisplayName("deriveMatchState returns FINISHED_WINNER2 when team2 reaches requiredToWin")
    void deriveMatchStateTeam2Wins() {
        assertThat(MatchFormat.BEST_OF_3.deriveMatchState(0, 2, 2))
                .isEqualTo(MatchState.FINISHED_WINNER2);
    }

    @Test
    @DisplayName("deriveMatchState returns FINISHED_STANDOFF for FIXED_2_SETS draw")
    void deriveMatchStateStandoff() {
        assertThat(MatchFormat.FIXED_2_SETS.deriveMatchState(1, 1, 2))
                .isEqualTo(MatchState.FINISHED_STANDOFF);
    }

    @Test
    @DisplayName("deriveMatchState returns ONCHECK for ongoing match")
    void deriveMatchStateOnCheck() {
        assertThat(MatchFormat.BEST_OF_3.deriveMatchState(1, 0, 1)).isEqualTo(MatchState.ONCHECK);
    }

    @Test
    @DisplayName("fromPersistedName resolves valid enum names")
    void fromPersistedNameResolvesValidName() {
        assertThat(MatchFormat.fromPersistedName("BEST_OF_3")).isEqualTo(MatchFormat.BEST_OF_3);
        assertThat(MatchFormat.fromPersistedName("FIXED_2_SETS"))
                .isEqualTo(MatchFormat.FIXED_2_SETS);
    }

    @Test
    @DisplayName("fromPersistedName throws IllegalArgumentException for unknown name")
    void fromPersistedNameThrowsForUnknownName() {
        assertThatThrownBy(() -> MatchFormat.fromPersistedName("BEST_OF_99"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BEST_OF_99");
    }

    @Test
    @DisplayName("fromPersistedName throws NullPointerException for null")
    void fromPersistedNameThrowsForNull() {
        assertThatThrownBy(() -> MatchFormat.fromPersistedName(null))
                .isInstanceOf(NullPointerException.class);
    }
}
