// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.tournament.RankedTeamEntry;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarRating;
import de.vvwt.tm.tournament.TeamSortCalculator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GroupPlacementSortCalculator} (E66S01 AC4, DEC-77 D-2/D-3, DEC-22).
 *
 * <p>Updated from E58S03: uses the new {@code rank(...)} interface returning a flat ranked list.
 * DEC-77 D-2: group_placement = group-major (all of G1 in placement order, then G2, ...). DEC-77
 * D-3: placement comparator.
 *
 * @see GroupPlacementSortCalculator
 * @see TeamSortCalculator
 * @see <a href="DEC-77">DEC-77 D-2/D-3</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="E66S01">E66S01 — AC4, AC5</a>
 */
@DisplayName("GroupPlacementSortCalculator unit tests — E66S01 AC4, AC5")
class GroupPlacementSortCalculatorTest {

    private final TeamSortCalculator calculator = new GroupPlacementSortCalculator();

    @Test
    @DisplayName("getKeyId() returns 'group_placement'")
    void getKeyId_returnsGroupPlacement() {
        assertThat(calculator.getKeyId()).isEqualTo("group_placement");
    }

    // -------------------------------------------------------------------------
    // AC4: group-major ordering
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("rank() with 2 groups/2 each: all G1 (placement order) then all G2")
    void rank_twoGroupsTwoEach_groupMajor() {
        UUID t1 = UUID.randomUUID(), t2 = UUID.randomUUID();
        UUID t3 = UUID.randomUUID(), t4 = UUID.randomUUID();

        TeamAvatar av1 = avatar(1, 1, t1); // G1
        TeamAvatar av2 = avatar(1, 2, t2); // G1
        TeamAvatar av3 = avatar(2, 1, t3); // G2
        TeamAvatar av4 = avatar(2, 2, t4); // G2

        // G1: av1 (80pts rank-1), av2 (30pts rank-2)
        // G2: av3 (70pts rank-1), av4 (10pts rank-2)
        Map<UUID, TeamAvatarRating> ratings =
                Map.of(
                        av1.getId(), rating(av1.getId(), 80, 1.0, 1.0, false),
                        av2.getId(), rating(av2.getId(), 30, 1.0, 1.0, false),
                        av3.getId(), rating(av3.getId(), 70, 1.0, 1.0, false),
                        av4.getId(), rating(av4.getId(), 10, 1.0, 1.0, false));
        Map<UUID, Team> teamById =
                Map.of(
                        t1, team(t1, 1, "T1"),
                        t2, team(t2, 2, "T2"),
                        t3, team(t3, 3, "T3"),
                        t4, team(t4, 4, "T4"));

        List<RankedTeamEntry> ranked =
                calculator.rank(List.of(av1, av2, av3, av4), ratings, teamById);

        assertThat(ranked).hasSize(4);
        // Group-major: all G1 first, then all G2
        // G1 placement order: av1(80pts), av2(30pts) → ranks 0,1
        // G2 placement order: av3(70pts), av4(10pts) → ranks 2,3
        assertThat(ranked.get(0).teamId()).isEqualTo(t1); // G1 rank-1
        assertThat(ranked.get(1).teamId()).isEqualTo(t2); // G1 rank-2
        assertThat(ranked.get(2).teamId()).isEqualTo(t3); // G2 rank-1
        assertThat(ranked.get(3).teamId()).isEqualTo(t4); // G2 rank-2
    }

    // -------------------------------------------------------------------------
    // AC5: DEC-77 D-3 comparator within each source group
    // -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "rank() within each source group uses DEC-77 D-3 comparator (ballQuotient tiebreak)")
    void rank_placementComparator_ballQuotientTiebreak() {
        UUID t1 = UUID.randomUUID(), t2 = UUID.randomUUID();
        TeamAvatar av1 = avatar(1, 1, t1); // points=10, setQ=1.5, ballQ=3.0
        TeamAvatar av2 = avatar(1, 2, t2); // points=10, setQ=1.5, ballQ=2.0

        Map<UUID, TeamAvatarRating> ratings =
                Map.of(
                        av1.getId(), rating(av1.getId(), 10, 1.5, 3.0, false),
                        av2.getId(), rating(av2.getId(), 10, 1.5, 2.0, false));
        Map<UUID, Team> teamById =
                Map.of(
                        t1, team(t1, 1, "T1"),
                        t2, team(t2, 2, "T2"));

        List<RankedTeamEntry> ranked = calculator.rank(List.of(av1, av2), ratings, teamById);

        assertThat(ranked).hasSize(2);
        assertThat(ranked.get(0).teamId()).isEqualTo(t1); // higher ballQuotient
    }

    @Test
    @DisplayName("rank() with empty fromAvatars returns empty list")
    void rank_emptyAvatars_returnsEmpty() {
        assertThat(calculator.rank(List.of(), Map.of(), Map.of())).isEmpty();
    }

    @Test
    @DisplayName("rank() carries fromAvatar.teamId (AC7 — not written to DB)")
    void rank_carriesTeamIdFromAvatar() {
        UUID t1 = UUID.randomUUID();
        TeamAvatar av1 = avatar(1, 1, t1);
        Map<UUID, Team> teamById = Map.of(t1, team(t1, 1, "T1"));

        List<RankedTeamEntry> ranked = calculator.rank(List.of(av1), Map.of(), teamById);

        assertThat(ranked.get(0).teamId()).isEqualTo(t1);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static TeamAvatar avatar(int groupNumber, int groupPosition, UUID teamId) {
        TeamAvatar av = new TeamAvatar();
        av.setId(UUID.randomUUID());
        av.setGroupNumber(groupNumber);
        av.setGroupPosition(groupPosition);
        av.setTeamId(teamId);
        return av;
    }

    private static Team team(UUID id, int number, String description) {
        Team t = new Team();
        t.setId(id);
        t.setTeamNumber(number);
        t.setDescription(description);
        return t;
    }

    private static TeamAvatarRating rating(
            UUID avatarId, int points, double setQ, double ballQ, boolean withoutAssessment) {
        TeamAvatarRating r = new TeamAvatarRating();
        r.setAvatarId(avatarId);
        r.setPoints(points);
        r.setSetQuotient(setQ);
        r.setBallQuotient(ballQ);
        r.setWithoutAssessment(withoutAssessment);
        return r;
    }
}
