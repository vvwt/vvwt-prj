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
 * Unit tests for {@link SequentialTeam2AvatarDistributor} (AC3, AC8, DEC-22).
 *
 * <p>E66S01: updated to the new {@code distribute(int teamCount, int groupCount)} interface (DEC-77
 * D-1). Same-package test: MAY white-box against implementation class per DEC-36.
 *
 * <p>Sequential algorithm: fill Group 1 fully before Group 2. positionsPerGroup = ceil(teamCount /
 * groupCount) targetGroup = (i / positionsPerGroup) + 1 targetPosition = (i % positionsPerGroup) +
 * 1
 *
 * @see SequentialTeam2AvatarDistributor
 * @see Team2AvatarDistributor
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="DEC-73">DEC-73 D-2 — Team2AvatarDistributor strategy interface</a>
 * @see <a href="DEC-77">DEC-77 D-1 — distribute(int, int) for every phase</a>
 * @see <a href="E58S02">E58S02 — AC3, AC8</a>
 * @see <a href="E66S01">E66S01 — AC3 (interface update)</a>
 */
class SequentialTeam2AvatarDistributorTest {

    private SequentialTeam2AvatarDistributor distributor;

    @BeforeEach
    void setUp() {
        distributor = new SequentialTeam2AvatarDistributor();
    }

    // ---------------------------------------------------------------------------
    // AC3: registry key
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("getKeyId() returns 'sequential'")
    void getKeyId_returnsSequential() {
        assertThat(distributor.getKeyId()).isEqualTo("sequential");
    }

    // ---------------------------------------------------------------------------
    // AC3: sequential distribution — 4 teams, 2 groups → Group1: [p1,p2], Group2: [p1,p2]
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("distribute(4, 2) fills Group1 before Group2")
    void distribute_4teams_2groups_fillsGroup1First() {
        List<Team2AvatarSlot> slots = distributor.distribute(4, 2);

        assertThat(slots).hasSize(4);
        // positionsPerGroup = ceil(4/2) = 2
        // i=0: group=1, pos=1; i=1: group=1, pos=2; i=2: group=2, pos=1; i=3: group=2, pos=2
        assertSlot(slots.get(0), 1, 1);
        assertSlot(slots.get(1), 1, 2);
        assertSlot(slots.get(2), 2, 1);
        assertSlot(slots.get(3), 2, 2);
    }

    // ---------------------------------------------------------------------------
    // AC3: 3 teams, 2 groups → positionsPerGroup=ceil(3/2)=2
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("distribute(3, 2) fills Group1 before Group2 (unequal sizes)")
    void distribute_3teams_2groups_unequalSizes() {
        List<Team2AvatarSlot> slots = distributor.distribute(3, 2);

        assertThat(slots).hasSize(3);
        // positionsPerGroup = ceil(3/2) = 2
        assertSlot(slots.get(0), 1, 1);
        assertSlot(slots.get(1), 1, 2);
        assertSlot(slots.get(2), 2, 1);
    }

    // ---------------------------------------------------------------------------
    // AC3: 6 teams, 3 groups
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("distribute(6, 3) fills in sequential order")
    void distribute_6teams_3groups() {
        List<Team2AvatarSlot> slots = distributor.distribute(6, 3);

        assertThat(slots).hasSize(6);
        assertSlot(slots.get(0), 1, 1);
        assertSlot(slots.get(1), 1, 2);
        assertSlot(slots.get(2), 2, 1);
        assertSlot(slots.get(3), 2, 2);
        assertSlot(slots.get(4), 3, 1);
        assertSlot(slots.get(5), 3, 2);
    }

    // ---------------------------------------------------------------------------
    // AC3: single group
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("distribute(3, 1) puts all in group 1")
    void distribute_3teams_1group() {
        List<Team2AvatarSlot> slots = distributor.distribute(3, 1);

        assertThat(slots).hasSize(3);
        assertSlot(slots.get(0), 1, 1);
        assertSlot(slots.get(1), 1, 2);
        assertSlot(slots.get(2), 1, 3);
    }

    // ---------------------------------------------------------------------------
    // Empty/zero guard
    // ---------------------------------------------------------------------------

    @Test
    @DisplayName("distribute(0, 2) returns empty list")
    void distribute_zeroTeams_returnsEmpty() {
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
