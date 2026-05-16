// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarProposal;
import de.vvwt.tm.tournament.TeamAvatarRating;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@link de.vvwt.tm.tournament.TeamSortCalculator} implementation for the {@code "team_number"}
 * sort mode.
 *
 * <p>Round-Robin distribution: avatars sorted by (group_number, group_position) ascending
 * (repository contract) are distributed across {@code groupCount} groups in round-robin order.
 * Avatar at index i (0-indexed) goes to group {@code (i % groupCount) + 1} with position {@code (i
 * / groupCount) + 1}.
 *
 * <p>Extracted from {@code DefaultPhaseTransitionService.computeTeamNumber} (E58S03 AC5). Behaviour
 * is identical to the original switch-case branch.
 *
 * @see de.vvwt.tm.tournament.TeamSortCalculator
 * @see AbstractAssignmentProposalCalculator
 * @see <a href="DEC-73">DEC-73 D-3 — TeamSortCalculator strategy</a>
 * @see <a href="E58S03">E58S03 — AC4, AC5, AC8</a>
 */
@Component("tmTeamNumberSortCalculator")
class TeamNumberSortCalculator extends AbstractAssignmentProposalCalculator {

    /** {@inheritDoc} */
    @Override
    public String getKeyId() {
        return "team_number";
    }

    /** {@inheritDoc} */
    @Override
    public List<TeamAvatarProposal> sortTeams(
            List<TeamAvatar> fromAvatars,
            Map<UUID, TeamAvatarRating> ratingsByAvatarId,
            Map<UUID, Team> teamById,
            int groupCount,
            String sortType) {
        // fromAvatars is already ordered by group_number, group_position (repository contract)
        List<TeamAvatarProposal> proposals = new ArrayList<>(fromAvatars.size());
        for (int i = 0; i < fromAvatars.size(); i++) {
            TeamAvatar av = fromAvatars.get(i);
            Team team = requireTeamForDisplay(av, teamById);
            int targetGroup = (i % groupCount) + 1;
            int targetPosition = (i / groupCount) + 1;
            proposals.add(buildProposal(av, team, targetGroup, targetPosition, sortType));
        }
        return proposals;
    }
}
