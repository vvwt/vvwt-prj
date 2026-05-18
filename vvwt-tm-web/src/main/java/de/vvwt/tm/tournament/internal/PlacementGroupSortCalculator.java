// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.PlacementComparator;
import de.vvwt.tm.tournament.RankedTeamEntry;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarRating;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@link de.vvwt.tm.tournament.TeamSortCalculator} implementation for the {@code "placement_group"}
 * sort mode.
 *
 * <p>DEC-77 D-2: rank-major (placement-first then source group): rank-1 of every source group, then
 * rank-2 of every source group, and so on. Within each rank tier, source groups appear in ascending
 * group-number order. Rank/placement order is defined by the DEC-77 D-3 comparator ({@link
 * PlacementComparator}): points DESC → setQuotient DESC → ballQuotient DESC → groupPosition ASC,
 * withoutAssessment last.
 *
 * <p>Returns a flat ordered list — no distribution is applied (DEC-77 D-1). Distribution is the
 * responsibility of {@link de.vvwt.tm.tournament.Team2AvatarDistributor}.
 *
 * @see de.vvwt.tm.tournament.TeamSortCalculator
 * @see AbstractAssignmentProposalCalculator
 * @see PlacementComparator
 * @see <a href="DEC-73">DEC-73 D-3 — TeamSortCalculator strategy</a>
 * @see <a href="DEC-77">DEC-77 D-2 — placement_group: rank-major</a>
 * @see <a href="DEC-77">DEC-77 D-3 — placement comparator</a>
 * @see <a href="E58S03">E58S03 — AC4, AC5, AC8 (original)</a>
 * @see <a href="E66S01">E66S01 — AC4, AC5 (updated: flat list, DEC-77 D-3 comparator)</a>
 */
@Component("tmPlacementGroupSortCalculator")
class PlacementGroupSortCalculator extends AbstractAssignmentProposalCalculator {

    /** {@inheritDoc} */
    @Override
    public String getKeyId() {
        return "placement_group";
    }

    /**
     * {@inheritDoc}
     *
     * <p>Interleaves source groups rank-major: rank-1 from all groups (ascending group number),
     * then rank-2, etc. Within each source group, teams are ranked by the DEC-77 D-3 placement
     * comparator.
     */
    @Override
    public List<RankedTeamEntry> rank(
            List<TeamAvatar> fromAvatars,
            Map<UUID, TeamAvatarRating> ratingsByAvatarId,
            Map<UUID, Team> teamById) {
        // Group avatars by sourceGroupNumber (ascending group number via LinkedHashMap insertion
        // order + explicit sort)
        Map<Integer, List<TeamAvatar>> byGroup = new LinkedHashMap<>();
        for (TeamAvatar av : fromAvatars) {
            byGroup.computeIfAbsent(av.getGroupNumber(), k -> new ArrayList<>()).add(av);
        }

        Comparator<TeamAvatar> cmp = PlacementComparator.forRatings(ratingsByAvatarId);

        // Sort each source group by DEC-77 D-3 comparator (rank 1 first within group)
        // Sort the group keys in ascending order for consistent cross-group interleaving
        List<Integer> groupKeys = new ArrayList<>(byGroup.keySet());
        groupKeys.sort(Comparator.naturalOrder());

        List<List<TeamAvatar>> sortedGroups = new ArrayList<>();
        for (Integer key : groupKeys) {
            List<TeamAvatar> groupAvatars = new ArrayList<>(byGroup.get(key));
            groupAvatars.sort(cmp);
            sortedGroups.add(groupAvatars);
        }

        // Determine maximum rank depth across all groups
        int maxSize = sortedGroups.stream().mapToInt(List::size).max().orElse(0);

        // Interleave rank-major: rank-0 from each group, then rank-1, etc.
        List<RankedTeamEntry> ranked = new ArrayList<>(fromAvatars.size());
        for (int rankSlot = 0; rankSlot < maxSize; rankSlot++) {
            for (List<TeamAvatar> group : sortedGroups) {
                if (rankSlot < group.size()) {
                    TeamAvatar av = group.get(rankSlot);
                    Team team = requireTeamForDisplay(av, teamById);
                    ranked.add(buildEntry(av, team));
                }
            }
        }
        return ranked;
    }
}
