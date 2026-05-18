// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarRating;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RED-first unit tests for {@link PlacementComparator} (AC5, DEC-77 D-3, DEC-22).
 *
 * <p>Placement/rank order: points DESC → setQuotient DESC → ballQuotient DESC → groupPosition ASC;
 * withoutAssessment last.
 *
 * @see PlacementComparator
 * @see <a href="DEC-77">DEC-77 D-3 — placement comparator</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (RED-first)</a>
 * @see <a href="E66S01">E66S01 — AC5</a>
 */
@DisplayName("PlacementComparator unit tests — E66S01 AC5")
class PlacementComparatorTest {

    // -------------------------------------------------------------------------
    // AC5-1: points DESC
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("higher points ranks first (DESC)")
    void compare_higherPointsFirst() {
        UUID idA = UUID.randomUUID(), idB = UUID.randomUUID();
        TeamAvatar avA = avatar(idA, 1, 1);
        TeamAvatar avB = avatar(idB, 1, 2);
        Map<UUID, TeamAvatarRating> ratings =
                Map.of(idA, rating(idA, 20, 1.0, 1.0, false), idB, rating(idB, 10, 1.0, 1.0, false));

        Comparator<TeamAvatar> cmp = PlacementComparator.forRatings(ratings);
        // avA (20 pts) ranks before avB (10 pts) → cmp(avA, avB) < 0
        assertThat(cmp.compare(avA, avB)).isLessThan(0);
        assertThat(cmp.compare(avB, avA)).isGreaterThan(0);
    }

    // -------------------------------------------------------------------------
    // AC5-2: setQuotient DESC (tie-break on points)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("equal points: higher setQuotient ranks first (DESC)")
    void compare_equalPoints_higherSetQuotientFirst() {
        UUID idA = UUID.randomUUID(), idB = UUID.randomUUID();
        TeamAvatar avA = avatar(idA, 1, 1);
        TeamAvatar avB = avatar(idB, 1, 2);
        Map<UUID, TeamAvatarRating> ratings =
                Map.of(idA, rating(idA, 10, 2.0, 1.0, false), idB, rating(idB, 10, 1.0, 1.0, false));

        Comparator<TeamAvatar> cmp = PlacementComparator.forRatings(ratings);
        assertThat(cmp.compare(avA, avB)).isLessThan(0); // avA better setQuotient
        assertThat(cmp.compare(avB, avA)).isGreaterThan(0);
    }

    // -------------------------------------------------------------------------
    // AC5-3: ballQuotient DESC (tie-break on points + setQuotient)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("equal points + setQuotient: higher ballQuotient ranks first (DESC)")
    void compare_equalPointsAndSetQuotient_higherBallQuotientFirst() {
        UUID idA = UUID.randomUUID(), idB = UUID.randomUUID();
        TeamAvatar avA = avatar(idA, 1, 1);
        TeamAvatar avB = avatar(idB, 1, 2);
        Map<UUID, TeamAvatarRating> ratings =
                Map.of(idA, rating(idA, 10, 1.5, 3.0, false), idB, rating(idB, 10, 1.5, 2.0, false));

        Comparator<TeamAvatar> cmp = PlacementComparator.forRatings(ratings);
        assertThat(cmp.compare(avA, avB)).isLessThan(0); // avA better ballQuotient
        assertThat(cmp.compare(avB, avA)).isGreaterThan(0);
    }

    // -------------------------------------------------------------------------
    // AC5-4: groupPosition ASC (tie-break on all quotients)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("all quotients equal: lower groupPosition ranks first (ASC)")
    void compare_allEqual_lowerGroupPositionFirst() {
        UUID idA = UUID.randomUUID(), idB = UUID.randomUUID();
        TeamAvatar avA = avatar(idA, 1, 1); // groupPosition=1
        TeamAvatar avB = avatar(idB, 1, 2); // groupPosition=2
        Map<UUID, TeamAvatarRating> ratings =
                Map.of(idA, rating(idA, 10, 1.5, 2.0, false), idB, rating(idB, 10, 1.5, 2.0, false));

        Comparator<TeamAvatar> cmp = PlacementComparator.forRatings(ratings);
        assertThat(cmp.compare(avA, avB)).isLessThan(0); // lower pos = better
        assertThat(cmp.compare(avB, avA)).isGreaterThan(0);
    }

    // -------------------------------------------------------------------------
    // AC5-5: withoutAssessment ranks last (below all assessed teams)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("withoutAssessment=true ranks last, below any assessed team")
    void compare_withoutAssessment_ranksLast() {
        UUID idA = UUID.randomUUID(), idB = UUID.randomUUID();
        TeamAvatar avA = avatar(idA, 1, 1); // assessed, 0 pts (worst)
        TeamAvatar avB = avatar(idB, 1, 2); // withoutAssessment — must still rank last
        Map<UUID, TeamAvatarRating> ratings =
                Map.of(idA, rating(idA, 0, 0.0, 0.0, false), idB, rating(idB, 999, 9.9, 9.9, true));

        Comparator<TeamAvatar> cmp = PlacementComparator.forRatings(ratings);
        // avB has highest raw scores but withoutAssessment=true → ranks behind avA
        assertThat(cmp.compare(avA, avB)).isLessThan(0); // avA ranks first
        assertThat(cmp.compare(avB, avA)).isGreaterThan(0);
    }

    @Test
    @DisplayName("two withoutAssessment teams are ordered deterministically by groupPosition ASC")
    void compare_bothWithoutAssessment_orderedByGroupPosition() {
        UUID idA = UUID.randomUUID(), idB = UUID.randomUUID();
        TeamAvatar avA = avatar(idA, 1, 1);
        TeamAvatar avB = avatar(idB, 1, 3);
        Map<UUID, TeamAvatarRating> ratings =
                Map.of(
                        idA, rating(idA, 0, 0.0, 0.0, true),
                        idB, rating(idB, 0, 0.0, 0.0, true));

        Comparator<TeamAvatar> cmp = PlacementComparator.forRatings(ratings);
        assertThat(cmp.compare(avA, avB)).isLessThan(0); // pos 1 < pos 3
    }

    // -------------------------------------------------------------------------
    // AC5-6: no rating → treated as withoutAssessment (below all assessed)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("avatar with no rating entry ranks last (same as withoutAssessment)")
    void compare_noRating_ranksLast() {
        UUID idA = UUID.randomUUID(), idB = UUID.randomUUID();
        TeamAvatar avA = avatar(idA, 1, 1); // has rating
        TeamAvatar avB = avatar(idB, 1, 2); // NO rating
        Map<UUID, TeamAvatarRating> ratings =
                Map.of(idA, rating(idA, 5, 1.0, 1.0, false));
        // avB has no entry in ratings map

        Comparator<TeamAvatar> cmp = PlacementComparator.forRatings(ratings);
        assertThat(cmp.compare(avA, avB)).isLessThan(0); // avA (rated) ranks before avB (unrated)
        assertThat(cmp.compare(avB, avA)).isGreaterThan(0);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static TeamAvatar avatar(UUID id, int groupNumber, int groupPosition) {
        TeamAvatar av = new TeamAvatar();
        av.setId(id);
        av.setGroupNumber(groupNumber);
        av.setGroupPosition(groupPosition);
        return av;
    }

    private static TeamAvatarRating rating(
            UUID avatarId, int points, double setQuotient, double ballQuotient, boolean withoutAssessment) {
        TeamAvatarRating r = new TeamAvatarRating();
        r.setAvatarId(avatarId);
        r.setPoints(points);
        r.setSetQuotient(setQuotient);
        r.setBallQuotient(ballQuotient);
        r.setWithoutAssessment(withoutAssessment);
        return r;
    }
}
