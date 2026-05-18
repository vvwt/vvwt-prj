// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.RankedTeamEntry;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarRating;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@link de.vvwt.tm.tournament.TeamSortCalculator} implementation for the {@code "team_number"}
 * sort mode.
 *
 * <p>DEC-77 D-2: ranks teams by team registration number ({@code teamNumber}) ascending. For Phase
 * 2+, the avatars are sorted by the {@code teamNumber} of their corresponding {@link Team} entity
 * (looked up via {@code teamById}). The result is a flat ordered list — the distribution across
 * groups is performed separately by {@link de.vvwt.tm.tournament.Team2AvatarDistributor}.
 *
 * @see de.vvwt.tm.tournament.TeamSortCalculator
 * @see AbstractAssignmentProposalCalculator
 * @see <a href="DEC-73">DEC-73 D-3 — TeamSortCalculator strategy</a>
 * @see <a href="DEC-77">DEC-77 D-2 — team_number: ascending by registration number</a>
 * @see <a href="E58S03">E58S03 — AC4, AC5, AC8 (original)</a>
 * @see <a href="E66S01">E66S01 — AC4 (updated to flat ranked list)</a>
 */
@Component("tmTeamNumberSortCalculator")
class TeamNumberSortCalculator extends AbstractAssignmentProposalCalculator {

    /** {@inheritDoc} */
    @Override
    public String getKeyId() {
        return "team_number";
    }

    /**
     * {@inheritDoc}
     *
     * <p>Sorts avatars by their team's {@code teamNumber} ascending. Returns a flat ranked list —
     * no group/position distribution is applied (that is the distributor's responsibility, DEC-77
     * D-1).
     *
     * <p>Ratings are not used for {@code team_number} ranking (sort is by registration number
     * only).
     */
    @Override
    public List<RankedTeamEntry> rank(
            List<TeamAvatar> fromAvatars,
            Map<UUID, TeamAvatarRating> ratingsByAvatarId,
            Map<UUID, Team> teamById) {
        // Sort avatars by teamNumber ASC
        List<TeamAvatar> sorted = new ArrayList<>(fromAvatars);
        sorted.sort(
                Comparator.comparingInt(av -> requireTeamForDisplay(av, teamById).getTeamNumber()));

        List<RankedTeamEntry> ranked = new ArrayList<>(sorted.size());
        for (TeamAvatar av : sorted) {
            Team team = requireTeamForDisplay(av, teamById);
            ranked.add(buildEntry(av, team));
        }
        return ranked;
    }
}
