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
 * {@link de.vvwt.tm.tournament.TeamSortCalculator} implementation for the {@code "placement_group"}
 * sort mode.
 *
 * <p>Placement-group distribution: teams keep their Phase-N group; positions within each group are
 * re-assigned by descending points (higher points = rank 1 = position 1). Teams with no rating are
 * placed at the end (effectively rank last — Integer.MIN_VALUE via {@link
 * AbstractAssignmentProposalCalculator#getPointsOrMin}).
 *
 * <p>Extracted from {@code DefaultPhaseTransitionService.computePlacementGroup} (E58S03 AC5).
 * Behaviour is identical to the original switch-case branch.
 *
 * @see de.vvwt.tm.tournament.TeamSortCalculator
 * @see AbstractAssignmentProposalCalculator
 * @see <a href="DEC-73">DEC-73 D-3 — TeamSortCalculator strategy</a>
 * @see <a href="E58S03">E58S03 — AC4, AC5, AC8</a>
 */
@Component("tmPlacementGroupSortCalculator")
class PlacementGroupSortCalculator extends AbstractAssignmentProposalCalculator {

    /** {@inheritDoc} */
    @Override
    public String getKeyId() {
        return "placement_group";
    }

    /** {@inheritDoc} */
    @Override
    public List<TeamAvatarProposal> sortTeams(
            List<TeamAvatar> fromAvatars,
            Map<UUID, TeamAvatarRating> ratingsByAvatarId,
            Map<UUID, Team> teamById,
            int groupCount,
            String sortType) {
        // Group avatars by their fromPhase groupNumber, preserving encounter order within each
        // group
        Map<Integer, List<TeamAvatar>> byGroup = new LinkedHashMap<>();
        for (TeamAvatar av : fromAvatars) {
            byGroup.computeIfAbsent(av.getGroupNumber(), k -> new ArrayList<>()).add(av);
        }

        List<TeamAvatarProposal> proposals = new ArrayList<>(fromAvatars.size());
        for (Map.Entry<Integer, List<TeamAvatar>> entry : byGroup.entrySet()) {
            int groupNumber = entry.getKey();
            List<TeamAvatar> groupAvatars = new ArrayList<>(entry.getValue());

            // Sort by descending points — higher points = better placement = lower position index
            groupAvatars.sort(
                    Comparator.comparingInt(
                                    (TeamAvatar av) ->
                                            getPointsOrMin(av.getId(), ratingsByAvatarId))
                            .reversed());

            for (int pos = 0; pos < groupAvatars.size(); pos++) {
                TeamAvatar av = groupAvatars.get(pos);
                Team team = requireTeamForDisplay(av, teamById);
                proposals.add(buildProposal(av, team, groupNumber, pos + 1, sortType));
            }
        }
        return proposals;
    }
}
