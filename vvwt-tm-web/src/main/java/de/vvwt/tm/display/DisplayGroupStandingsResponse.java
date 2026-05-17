// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.display;

import java.util.List;
import java.util.UUID;

/**
 * Bounded-context-owned query-shape record for the display group standings response (E25S01).
 *
 * <p>Resides at {@code de.vvwt.tm.display.*} (root, public) per DEC-40 §2026-04-27 Clarification
 * (Pattern A — bounded-context-owned query-shape records). JSON wire shape preserved verbatim per
 * Brief v2.2 C-8 + D-9 and C-3 signature-preservation.
 *
 * <p>Rankings within each group are sorted per D-33: {@code points DESC}, then {@code setQuotient
 * DESC}, then {@code ballQuotient DESC}, {@code withoutAssessment} last. {@code position} is
 * 1-indexed within each group.
 *
 * <p>Nested records {@code GroupStandings} and {@code TeamRanking} are co-located per Java records
 * canon.
 *
 * @param phaseId UUID of the active phase
 * @param groups ordered list of group standings (by group number)
 * @see DisplayOverviewService
 * @see E25S01
 */
public record DisplayGroupStandingsResponse(UUID phaseId, List<GroupStandings> groups) {

    /**
     * Standings for a single group.
     *
     * @param groupNumber 1-based group number
     * @param rankings team rankings sorted per D-33, position 1-indexed
     */
    public record GroupStandings(int groupNumber, List<TeamRanking> rankings) {}

    /**
     * Ranking entry for a single team within a group.
     *
     * @param position 1-based rank within the group (1 = best)
     * @param teamName team name (resolved via TeamAvatar → Team)
     * @param points tournament points accumulated
     * @param setsWon sets won
     * @param setsLost sets lost
     * @param ballsWon points (balls) won across all sets
     * @param ballsLost points (balls) lost across all sets
     */
    public record TeamRanking(
            int position,
            String teamName,
            int points,
            int setsWon,
            int setsLost,
            int ballsWon,
            int ballsLost) {}
}
