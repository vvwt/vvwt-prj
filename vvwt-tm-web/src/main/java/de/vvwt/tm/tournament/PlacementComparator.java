// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

/**
 * Placement comparator for ranking {@link TeamAvatar}s by their Phase-N results (DEC-77 D-3).
 *
 * <p>Order (highest rank first):
 *
 * <ol>
 *   <li>{@code points} descending
 *   <li>{@code setQuotient} descending (tie-break)
 *   <li>{@code ballQuotient} descending (tie-break)
 *   <li>{@code groupPosition} ascending (deterministic prior-seeding tie-break; lower position =
 *       better prior placement per project convention)
 * </ol>
 *
 * <p>A team flagged {@link TeamAvatarRating#isWithoutAssessment()} ranks below every assessed team.
 * An avatar with no rating entry in the map is treated as unrated (same as {@code
 * withoutAssessment=true}).
 *
 * <p>This comparator is the single source of truth for "what ranks higher" (DEC-77 D-6). It is
 * shared by:
 *
 * <ul>
 *   <li>The phase-transition proposal computation ({@link de.vvwt.tm.tournament.TeamSortCalculator}
 *       implementations {@code placement_group} and {@code group_placement}).
 *   <li>The Display Overview group-standings panel ({@link
 *       de.vvwt.tm.display.internal.DefaultDisplayOverviewService#getGroupStandings(String)}).
 * </ul>
 *
 * <p>Declared in the {@code tournament} public root package so all bounded contexts that depend on
 * {@code tournament} can access it without violating Modulith internal-package boundaries. Not a
 * Spring bean (stateful factory pattern). Not subject to DEC-58 Clause A (excluded by Clause D —
 * comparator utility, not a service-shaped primary-port bean).
 *
 * @see TeamSortCalculator
 * @see <a href="DEC-77">DEC-77 D-3 — placement comparator definition</a>
 * @see <a href="DEC-77">DEC-77 D-6 — Display standings must use this comparator</a>
 * @see <a href="DEC-35">DEC-35 — impl in .internal (service/repo impls; comparator utility exempt)</a>
 * @see <a href="E66S01">E66S01 — AC5 (introduced)</a>
 * @see <a href="E66S04">E66S04 — AC3 (moved to public package for Display overview reuse)</a>
 */
public final class PlacementComparator implements Comparator<TeamAvatar> {

    private final Map<UUID, TeamAvatarRating> ratingsByAvatarId;

    private PlacementComparator(Map<UUID, TeamAvatarRating> ratingsByAvatarId) {
        this.ratingsByAvatarId = ratingsByAvatarId;
    }

    /**
     * Factory method — creates a {@link PlacementComparator} backed by the given ratings map.
     *
     * @param ratingsByAvatarId bulk-loaded ratings for the avatars to rank; must not be {@code
     *     null}; may be empty (all avatars treated as unrated)
     * @return a comparator ordering avatars by DEC-77 D-3 placement order (highest rank first)
     */
    public static Comparator<TeamAvatar> forRatings(Map<UUID, TeamAvatarRating> ratingsByAvatarId) {
        return new PlacementComparator(ratingsByAvatarId);
    }

    @Override
    public int compare(TeamAvatar a, TeamAvatar b) {
        TeamAvatarRating rA = ratingsByAvatarId.get(a.getId());
        TeamAvatarRating rB = ratingsByAvatarId.get(b.getId());

        boolean unratedA = rA == null || rA.isWithoutAssessment();
        boolean unratedB = rB == null || rB.isWithoutAssessment();

        // withoutAssessment / unrated ranks last
        if (unratedA && !unratedB) {
            return 1; // a ranks behind b
        }
        if (!unratedA && unratedB) {
            return -1; // a ranks ahead of b
        }

        if (unratedA) {
            // Both unrated — deterministic tie-break by groupPosition ASC
            return Integer.compare(a.getGroupPosition(), b.getGroupPosition());
        }

        // Both assessed — apply DEC-77 D-3 comparator
        // 1. points DESC
        int cmp = Integer.compare(rB.getPoints(), rA.getPoints());
        if (cmp != 0) {
            return cmp;
        }
        // 2. setQuotient DESC
        cmp = Double.compare(rB.getSetQuotient(), rA.getSetQuotient());
        if (cmp != 0) {
            return cmp;
        }
        // 3. ballQuotient DESC
        cmp = Double.compare(rB.getBallQuotient(), rA.getBallQuotient());
        if (cmp != 0) {
            return cmp;
        }
        // 4. groupPosition ASC (deterministic prior-seeding tie-break)
        return Integer.compare(a.getGroupPosition(), b.getGroupPosition());
    }
}
