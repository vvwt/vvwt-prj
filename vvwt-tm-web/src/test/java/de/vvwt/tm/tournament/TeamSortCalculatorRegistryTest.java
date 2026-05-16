// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.vvwt.tm.tournament.internal.DefaultTeamSortCalculatorRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RED-first unit tests for {@link TeamSortCalculatorRegistry} via {@link
 * DefaultTeamSortCalculatorRegistry} (AC2, AC4, AC8, DEC-22).
 *
 * <p>Cross-package test in {@code de.vvwt.tm.tournament}: uses {@link TeamSortCalculatorRegistry}
 * interface per DEC-36. Implementation class directly referenced in constructor call for
 * construction.
 *
 * @see TeamSortCalculatorRegistry
 * @see DefaultTeamSortCalculatorRegistry
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="DEC-36">DEC-36 — cross-package test typing rule</a>
 * @see <a href="DEC-73">DEC-73 D-3 — TeamSortCalculatorRegistry</a>
 * @see <a href="E58S03">E58S03 — AC2, AC4</a>
 */
class TeamSortCalculatorRegistryTest {

    // ---------------------------------------------------------------------------
    // AC2: registry resolves all three keys
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("get('team_number') returns the team_number calculator")
    void get_teamNumber_returnsTeamNumberCalculator() {
        TeamSortCalculator teamNumber = mockCalculator("team_number");
        TeamSortCalculator placementGroup = mockCalculator("placement_group");
        TeamSortCalculator groupPlacement = mockCalculator("group_placement");

        TeamSortCalculatorRegistry registry =
                new DefaultTeamSortCalculatorRegistry(
                        List.of(teamNumber, placementGroup, groupPlacement));

        assertThat(registry.get("team_number")).isSameAs(teamNumber);
    }

    @Test
    @DisplayName("get('placement_group') returns the placement_group calculator")
    void get_placementGroup_returnsPlacementGroupCalculator() {
        TeamSortCalculator teamNumber = mockCalculator("team_number");
        TeamSortCalculator placementGroup = mockCalculator("placement_group");
        TeamSortCalculator groupPlacement = mockCalculator("group_placement");

        TeamSortCalculatorRegistry registry =
                new DefaultTeamSortCalculatorRegistry(
                        List.of(teamNumber, placementGroup, groupPlacement));

        assertThat(registry.get("placement_group")).isSameAs(placementGroup);
    }

    @Test
    @DisplayName("get('group_placement') returns the group_placement calculator")
    void get_groupPlacement_returnsGroupPlacementCalculator() {
        TeamSortCalculator teamNumber = mockCalculator("team_number");
        TeamSortCalculator placementGroup = mockCalculator("placement_group");
        TeamSortCalculator groupPlacement = mockCalculator("group_placement");

        TeamSortCalculatorRegistry registry =
                new DefaultTeamSortCalculatorRegistry(
                        List.of(teamNumber, placementGroup, groupPlacement));

        assertThat(registry.get("group_placement")).isSameAs(groupPlacement);
    }

    @Test
    @DisplayName("knownKeys() contains all three sort mode keys")
    void knownKeys_containsAllThreeKeys() {
        TeamSortCalculator teamNumber = mockCalculator("team_number");
        TeamSortCalculator placementGroup = mockCalculator("placement_group");
        TeamSortCalculator groupPlacement = mockCalculator("group_placement");

        TeamSortCalculatorRegistry registry =
                new DefaultTeamSortCalculatorRegistry(
                        List.of(teamNumber, placementGroup, groupPlacement));

        assertThat(registry.knownKeys())
                .containsExactlyInAnyOrder("team_number", "placement_group", "group_placement");
    }

    // ---------------------------------------------------------------------------
    // AC2: unknown key throws
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("get('unknown') throws IllegalArgumentException")
    void get_unknownKey_throwsIllegalArgumentException() {
        TeamSortCalculatorRegistry registry =
                new DefaultTeamSortCalculatorRegistry(List.of(mockCalculator("team_number")));

        assertThatThrownBy(() -> registry.get("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown");
    }

    // ---------------------------------------------------------------------------
    // AC2: duplicate key at startup fails fast
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("duplicate key at construction throws IllegalStateException")
    void constructor_duplicateKey_throwsIllegalStateException() {
        TeamSortCalculator c1 = mockCalculator("team_number");
        TeamSortCalculator c2 = mockCalculator("team_number");

        assertThatThrownBy(() -> new DefaultTeamSortCalculatorRegistry(List.of(c1, c2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("team_number");
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private static TeamSortCalculator mockCalculator(String key) {
        TeamSortCalculator c = mock(TeamSortCalculator.class);
        when(c.getKeyId()).thenReturn(key);
        return c;
    }
}
