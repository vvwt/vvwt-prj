// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarProposal;
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
 * <p>Group-placement distribution: rank-N finisher from every Phase-N group → target group N.
 * Truncates to the minimum group size when groups are unequal — teams with rank beyond the minimum
 * are excluded (no equivalent rank slot in the smaller group).
 *
 * <p>Within each target group, positions are assigned in the order the source groups are
 * encountered (source group 1 first, source group 2 second, etc.).
 *
 * <p>Extracted from {@code DefaultPhaseTransitionService.computeGroupPlacement} (E58S03 AC5).
 * Behaviour is identical to the original switch-case branch.
 *
 * @see de.vvwt.tm.tournament.TeamSortCalculator
 * @see AbstractAssignmentProposalCalculator
 * @see <a href="DEC-73">DEC-73 D-3 — TeamSortCalculator strategy</a>
 * @see <a href="E58S03">E58S03 — AC4, AC5, AC8</a>
 */
@Component("tmGroupPlacementSortCalculator")
class GroupPlacementSortCalculator extends AbstractAssignmentProposalCalculator {

    /** {@inheritDoc} */
    @Override
    public String getKeyId() {
        return "group_placement";
    }

    /** {@inheritDoc} */
    @Override
    public List<TeamAvatarProposal> sortTeams(
            List<TeamAvatar> fromAvatars,
            Map<UUID, TeamAvatarRating> ratingsByAvatarId,
            Map<UUID, Team> teamById,
            int groupCount,
            String sortType) {
        // Group avatars by their fromPhase groupNumber
        Map<Integer, List<TeamAvatar>> byGroup = new LinkedHashMap<>();
        for (TeamAvatar av : fromAvatars) {
            byGroup.computeIfAbsent(av.getGroupNumber(), k -> new ArrayList<>()).add(av);
        }

        // Sort each group by descending points: index 0 = rank 1 (best), index 1 = rank 2, ...
        for (List<TeamAvatar> groupAvatars : byGroup.values()) {
            groupAvatars.sort(
                    Comparator.comparingInt(
                                    (TeamAvatar av) ->
                                            getPointsOrMin(av.getId(), ratingsByAvatarId))
                            .reversed());
        }

        // Truncate to minimum group size
        int minSize = byGroup.values().stream().mapToInt(List::size).min().orElse(0);

        // Build proposals: rank slot r → target group (r+1), position = source-group order
        List<TeamAvatarProposal> proposals = new ArrayList<>();
        for (int rankSlot = 0; rankSlot < minSize; rankSlot++) {
            int targetGroup = rankSlot + 1;
            int posWithinGroup = 1;
            for (List<TeamAvatar> sourceGroup : byGroup.values()) {
                TeamAvatar av = sourceGroup.get(rankSlot);
                Team team = requireTeamForDisplay(av, teamById);
                proposals.add(buildProposal(av, team, targetGroup, posWithinGroup, sortType));
                posWithinGroup++;
            }
        }
        return proposals;
    }
}
