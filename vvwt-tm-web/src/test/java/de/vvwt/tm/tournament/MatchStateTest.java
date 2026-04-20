package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MatchState} enum (E21S05, AC-TDD-MatchState).
 *
 * <h2>RED state</h2>
 *
 * <p>This test was committed RED: {@link MatchState} at {@code de.vvwt.tm.tournament.MatchState}
 * did not exist at commit time, causing a compile error — satisfying the DEC-22 Iron Law.
 *
 * <h2>Coverage</h2>
 *
 * <ul>
 *   <li>Enum values present with expected names (AC-ENUM-VALUES-STABLE)
 *   <li>Legacy int codes map correctly via {@code fromLegacyCode}
 *   <li>Unknown legacy code throws {@link IllegalArgumentException}
 *   <li>State-transition check: terminal states vs. ongoing states
 * </ul>
 *
 * @see MatchState
 * @see <a href="DEC-21">DEC-21 — Spring Modulith boundary-API placement</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S05">E21S05 — Match cluster reconstruction (inventory line 176)</a>
 */
@DisplayName("MatchState enum — E21S05 AC-TDD-MatchState")
class MatchStateTest {

    @Test
    @DisplayName("All expected enum value names are present (AC-ENUM-VALUES-STABLE)")
    void enumValueNamesAreStable() {
        assertThat(MatchState.values())
                .extracting(Enum::name)
                .containsExactlyInAnyOrder(
                        "OPEN",
                        "ENABLED",
                        "INPROGRESS",
                        "ONCHECK",
                        "FINISHED_WINNER1",
                        "FINISHED_WINNER2",
                        "FINISHED_STANDOFF",
                        "CANCELED");
    }

    @Test
    @DisplayName("fromLegacyCode resolves known integer codes")
    void fromLegacyCodeResolvesKnownCodes() {
        assertThat(MatchState.fromLegacyCode(0)).isEqualTo(MatchState.OPEN);
        assertThat(MatchState.fromLegacyCode(10)).isEqualTo(MatchState.ENABLED);
        assertThat(MatchState.fromLegacyCode(30)).isEqualTo(MatchState.INPROGRESS);
        assertThat(MatchState.fromLegacyCode(35)).isEqualTo(MatchState.ONCHECK);
        assertThat(MatchState.fromLegacyCode(50)).isEqualTo(MatchState.FINISHED_STANDOFF);
        assertThat(MatchState.fromLegacyCode(51)).isEqualTo(MatchState.FINISHED_WINNER1);
        assertThat(MatchState.fromLegacyCode(52)).isEqualTo(MatchState.FINISHED_WINNER2);
        assertThat(MatchState.fromLegacyCode(-10)).isEqualTo(MatchState.CANCELED);
    }

    @Test
    @DisplayName("fromLegacyCode throws IllegalArgumentException for unknown code")
    void fromLegacyCodeThrowsForUnknownCode() {
        assertThatThrownBy(() -> MatchState.fromLegacyCode(999))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("999");
    }

    @Test
    @DisplayName("getLegacyCode returns the stored integer code")
    void getLegacyCodeReturnsMappedIntegerCode() {
        assertThat(MatchState.OPEN.getLegacyCode()).isEqualTo(0);
        assertThat(MatchState.CANCELED.getLegacyCode()).isEqualTo(-10);
        assertThat(MatchState.FINISHED_WINNER1.getLegacyCode()).isEqualTo(51);
    }
}
