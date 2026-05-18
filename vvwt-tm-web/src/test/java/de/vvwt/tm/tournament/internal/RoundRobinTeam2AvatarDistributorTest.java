// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.vvwt.tm.tournament.Team2AvatarDistributor;
import de.vvwt.tm.tournament.Team2AvatarSlot;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RoundRobinTeam2AvatarDistributor} (AC3, AC8, DEC-22).
 *
 * <p>E66S01: updated to the new {@code distribute(int teamCount, int groupCount)} interface
 * (DEC-77 D-1). Same-package test: MAY white-box against implementation class per DEC-36.
 *
 * <p>Round-Robin algorithm: distribute one team per group before advancing position. targetGroup =
 * (i % groupCount) + 1 targetPosition = (i / groupCount) + 1
 *
 * @see RoundRobinTeam2AvatarDistributor
 * @see Team2AvatarDistributor
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="DEC-73">DEC-73 D-2 — Team2AvatarDistributor strategy interface</a>
 * @see <a href="DEC-77">DEC-77 D-1 — distribute(int, int) for every phase</a>
 * @see <a href="E58S02">E58S02 — AC3, AC8</a>
 * @see <a href="E66S01">E66S01 — AC3 (interface update)</a>
 */
class RoundRobinTeam2AvatarDistributorTest {

    private RoundRobinTeam2AvatarDistributor distributor;

    @BeforeEach
    void setUp() {
        distributor = new RoundRobinTeam2AvatarDistributor();
    }

    // ---------------------------------------------------------------------------
    // AC3: registry key
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("getKeyId() returns 'round_robin'")
    void getKeyId_returnsRoundRobin() {
        assertThat(distributor.getKeyId()).isEqualTo("round_robin");
    }

    // ---------------------------------------------------------------------------
    // AC3: round_robin distribution — 4 teams, 2 groups
    // i=0: g1,p1; i=1: g2,p1; i=2: g1,p2; i=3: g2,p2
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("distribute(4, 2) distributes one-per-group before advancing")
    void distribute_4teams_2groups_roundRobin() {
        List<Team2AvatarSlot> slots = distributor.distribute(4, 2);

        assertThat(slots).hasSize(4);
        assertSlot(slots.get(0), 1, 1);
        assertSlot(slots.get(1), 2, 1);
        assertSlot(slots.get(2), 1, 2);
        assertSlot(slots.get(3), 2, 2);
    }

    // ---------------------------------------------------------------------------
    // AC3: 3 teams, 2 groups (unequal)
    // i=0: g1,p1; i=1: g2,p1; i=2: g1,p2
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("distribute(3, 2) distributes round-robin (unequal sizes)")
    void distribute_3teams_2groups_unequal() {
        List<Team2AvatarSlot> slots = distributor.distribute(3, 2);

        assertThat(slots).hasSize(3);
        assertSlot(slots.get(0), 1, 1);
        assertSlot(slots.get(1), 2, 1);
        assertSlot(slots.get(2), 1, 2);
    }

    // ---------------------------------------------------------------------------
    // AC3: 6 teams, 3 groups
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("distribute(6, 3) round-robins across groups")
    void distribute_6teams_3groups() {
        List<Team2AvatarSlot> slots = distributor.distribute(6, 3);

        assertThat(slots).hasSize(6);
        assertSlot(slots.get(0), 1, 1);
        assertSlot(slots.get(1), 2, 1);
        assertSlot(slots.get(2), 3, 1);
        assertSlot(slots.get(3), 1, 2);
        assertSlot(slots.get(4), 2, 2);
        assertSlot(slots.get(5), 3, 2);
    }

    // ---------------------------------------------------------------------------
    // Empty/zero guard
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("distribute(0, 2) returns empty list")
    void distribute_emptyTeams_returnsEmpty() {
        assertThat(distributor.distribute(0, 2)).isEmpty();
    }

    @Test
    @DisplayName("distribute() with groupCount < 1 throws IllegalArgumentException")
    void distribute_groupCountLessThanOne_throws() {
        assertThatThrownBy(() -> distributor.distribute(2, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("distribute() with negative teamCount throws IllegalArgumentException")
    void distribute_negativeTeamCount_throws() {
        assertThatThrownBy(() -> distributor.distribute(-1, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private static void assertSlot(Team2AvatarSlot slot, int expectedGroup, int expectedPosition) {
        assertThat(slot.groupNumber()).as("groupNumber").isEqualTo(expectedGroup);
        assertThat(slot.groupPosition()).as("groupPosition").isEqualTo(expectedPosition);
    }
}
