// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.RankedTeamEntry;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarRating;
import de.vvwt.tm.tournament.TeamSortCalculator;
import java.util.Map;
import java.util.UUID;

/**
 * Abstract base for {@link TeamSortCalculator} implementations.
 *
 * <p>Provides shared helpers to the three concrete sort-mode implementations:
 *
 * <ul>
 *   <li>{@link #requireTeamForDisplay(TeamAvatar, Map)} — resolves the {@link Team} aggregate for a
 *       given avatar (throws {@link IllegalStateException} on missing data).
 *   <li>{@link #buildEntry(TeamAvatar, Team)} — constructs a {@link RankedTeamEntry} from an avatar
 *       and its resolved Team, carrying source-phase structural coordinates.
 * </ul>
 *
 * <p>Placed in the internal package per DEC-35 (abstract base class, not a public surface type).
 *
 * @see TeamSortCalculator
 * @see <a href="DEC-35">DEC-35 — impl in .internal</a>
 * @see <a href="DEC-59">DEC-59 — operator-confirmation workflow; teamId not set by calculator</a>
 * @see <a href="DEC-73">DEC-73 D-3 — AbstractAssignmentProposalCalculator base</a>
 * @see <a href="DEC-77">DEC-77 D-1 — flat ranked list output</a>
 * @see <a href="E58S03">E58S03 — AC3</a>
 * @see <a href="E66S01">E66S01 — AC2, AC7 (updated to flat ranked list)</a>
 */
abstract class AbstractAssignmentProposalCalculator implements TeamSortCalculator {

    /**
     * Resolves the {@link Team} aggregate for the given avatar's {@code teamId}.
     *
     * @param avatar the avatar whose team to resolve
     * @param teamById lookup map from teamId → Team (pre-built by caller)
     * @return the resolved Team; never {@code null}
     * @throws IllegalStateException if the team cannot be found (corrupt data defense per E48S20)
     */
    protected Team requireTeamForDisplay(TeamAvatar avatar, Map<UUID, Team> teamById) {
        UUID teamId = avatar.getTeamId();
        Team team = teamById.get(teamId);
        if (team == null) {
            throw new IllegalStateException(
                    "Cannot resolve Team for display: no Team found for teamId="
                            + teamId
                            + " (avatar.id="
                            + avatar.getId()
                            + "). Data integrity issue (AC-ERROR-MISSING-TEAM-DEFENSE, E48S20).");
        }
        return team;
    }

    /**
     * Constructs a {@link RankedTeamEntry} for a Phase-2+ avatar.
     *
     * <p>Carries the avatar's {@code teamId} (for proposal-building) and the source-phase structural
     * coordinates ({@code sourceGroupNumber}, {@code sourceGroupPosition}) as informational display
     * fields. Per AC7 / DEC-59 Clause C, no teamId is written to the database by the calculator.
     *
     * @param fromAvatar the predecessor-phase avatar (source of teamId and structural identity)
     * @param team the resolved Team for display fields
     * @return a {@link RankedTeamEntry} carrying the from-avatar's identity and display fields
     */
    protected RankedTeamEntry buildEntry(TeamAvatar fromAvatar, Team team) {
        return new RankedTeamEntry(
                fromAvatar.getTeamId(),
                team.getTeamNumber(),
                team.getDescription(),
                fromAvatar.getGroupNumber(),
                fromAvatar.getGroupPosition());
    }
}
