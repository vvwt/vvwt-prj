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
 * {@link de.vvwt.tm.tournament.TeamSortCalculator} implementation for the {@code "group_placement"}
 * sort mode.
 *
 * <p>DEC-77 D-2: group-major (source-group-first then placement): all teams of source group 1 in
 * placement order, then all teams of source group 2, and so on. Source groups are visited in
 * ascending group-number order. Placement order within each group is defined by the DEC-77 D-3
 * comparator ({@link PlacementComparator}): points DESC → setQuotient DESC → ballQuotient DESC →
 * groupPosition ASC, withoutAssessment last.
 *
 * <p>Returns a flat ordered list — no distribution is applied (DEC-77 D-1). Distribution is the
 * responsibility of {@link de.vvwt.tm.tournament.Team2AvatarDistributor}.
 *
 * @see de.vvwt.tm.tournament.TeamSortCalculator
 * @see AbstractAssignmentProposalCalculator
 * @see PlacementComparator
 * @see <a href="DEC-73">DEC-73 D-3 — TeamSortCalculator strategy</a>
 * @see <a href="DEC-77">DEC-77 D-2 — group_placement: group-major</a>
 * @see <a href="DEC-77">DEC-77 D-3 — placement comparator</a>
 * @see <a href="E58S03">E58S03 — AC4, AC5, AC8 (original)</a>
 * @see <a href="E66S01">E66S01 — AC4, AC5 (updated: flat list, DEC-77 D-3 comparator)</a>
 */
@Component("tmGroupPlacementSortCalculator")
class GroupPlacementSortCalculator extends AbstractAssignmentProposalCalculator {

    /** {@inheritDoc} */
    @Override
    public String getKeyId() {
        return "group_placement";
    }

    /**
     * {@inheritDoc}
     *
     * <p>Emits all teams from source group 1 (in DEC-77 D-3 placement order), then all from source
     * group 2, and so on (group-major ordering). Source groups are visited in ascending
     * group-number order.
     */
    @Override
    public List<RankedTeamEntry> rank(
            List<TeamAvatar> fromAvatars,
            Map<UUID, TeamAvatarRating> ratingsByAvatarId,
            Map<UUID, Team> teamById) {
        // Group avatars by sourceGroupNumber
        Map<Integer, List<TeamAvatar>> byGroup = new LinkedHashMap<>();
        for (TeamAvatar av : fromAvatars) {
            byGroup.computeIfAbsent(av.getGroupNumber(), k -> new ArrayList<>()).add(av);
        }

        Comparator<TeamAvatar> cmp = PlacementComparator.forRatings(ratingsByAvatarId);

        // Sort group keys ascending for consistent ordering
        List<Integer> groupKeys = new ArrayList<>(byGroup.keySet());
        groupKeys.sort(Comparator.naturalOrder());

        // Group-major: emit all of group 1 in placement order, then group 2, etc.
        List<RankedTeamEntry> ranked = new ArrayList<>(fromAvatars.size());
        for (Integer key : groupKeys) {
            List<TeamAvatar> groupAvatars = new ArrayList<>(byGroup.get(key));
            groupAvatars.sort(cmp);
            for (TeamAvatar av : groupAvatars) {
                Team team = requireTeamForDisplay(av, teamById);
                ranked.add(buildEntry(av, team));
            }
        }
        return ranked;
    }
}
