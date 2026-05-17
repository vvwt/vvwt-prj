// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SetState} enum (E21S05, AC-TDD-SetState).
 *
 * <h2>RED state</h2>
 *
 * <p>This test was committed RED: {@link SetState} at {@code de.vvwt.tm.tournament.SetState} did
 * not exist at commit time, causing a compile error — satisfying the DEC-22 Iron Law.
 *
 * <h2>Coverage</h2>
 *
 * <ul>
 *   <li>Enum value names stable (AC-ENUM-VALUES-STABLE)
 *   <li>Legacy int codes resolve correctly via {@code fromLegacyCode}
 *   <li>Unknown code throws {@link IllegalArgumentException}
 * </ul>
 *
 * @see SetState
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E21S05">E21S05 — inventory line 184</a>
 */
@DisplayName("SetState enum — E21S05 AC-TDD-SetState")
class SetStateTest {

    @Test
    @DisplayName("All expected enum value names are present (AC-ENUM-VALUES-STABLE)")
    void enumValueNamesAreStable() {
        assertThat(SetState.values())
                .extracting(Enum::name)
                .containsExactlyInAnyOrder("OPEN", "WINNER1", "WINNER2", "STANDOFF", "CANCELED");
    }

    @Test
    @DisplayName("fromLegacyCode resolves known integer codes")
    void fromLegacyCodeResolvesKnownCodes() {
        assertThat(SetState.fromLegacyCode(0)).isEqualTo(SetState.OPEN);
        assertThat(SetState.fromLegacyCode(1)).isEqualTo(SetState.WINNER1);
        assertThat(SetState.fromLegacyCode(2)).isEqualTo(SetState.WINNER2);
        assertThat(SetState.fromLegacyCode(3)).isEqualTo(SetState.STANDOFF);
        assertThat(SetState.fromLegacyCode(-1)).isEqualTo(SetState.CANCELED);
    }

    @Test
    @DisplayName("fromLegacyCode throws IllegalArgumentException for unknown code")
    void fromLegacyCodeThrowsForUnknownCode() {
        assertThatThrownBy(() -> SetState.fromLegacyCode(999))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("999");
    }

    @Test
    @DisplayName("getLegacyCode returns the stored integer code")
    void getLegacyCodeReturnsMappedInteger() {
        assertThat(SetState.OPEN.getLegacyCode()).isEqualTo(0);
        assertThat(SetState.CANCELED.getLegacyCode()).isEqualTo(-1);
        assertThat(SetState.WINNER1.getLegacyCode()).isEqualTo(1);
    }
}
