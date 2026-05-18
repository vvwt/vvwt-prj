// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Strategy interface for ranking teams from a predecessor phase into a flat ordered list.
 *
 * <p>Each implementation encodes one of the three team-sort modes ({@code "team_number"}, {@code
 * "placement_group"}, {@code "group_placement"}) that govern how teams are ranked before
 * distribution into Phase-N+1 avatar slots.
 *
 * <p>The returned list is a flat ranking — the element at index 0 is the highest-ranked team; the
 * element at the last index is the lowest-ranked team. No {@code (groupNumber, groupPosition)}
 * distribution is embedded in the output — that is the responsibility of {@link
 * Team2AvatarDistributor} (DEC-77 D-1).
 *
 * <p>The calculator receives all information it needs as parameters. It does NOT call any
 * repository itself (DEC-59 Clause C / AC7 — {@code teamId} is written only by the
 * operator-confirmation handler; the ranking merely proposes the order).
 *
 * <p>DEC-58 Clause A + DEC-72: public interface in the bounded-context root package {@code
 * de.vvwt.tm.tournament}; implementations live in {@code de.vvwt.tm.tournament.internal} (DEC-35).
 *
 * @see TeamSortCalculatorRegistry
 * @see RankedTeamEntry
 * @see Team2AvatarDistributor
 * @see <a href="DEC-35">DEC-35 — interface in public package, impl in .internal</a>
 * @see <a href="DEC-58">DEC-58 — universal interface mandate</a>
 * @see <a href="DEC-73">DEC-73 D-3 — TeamSortCalculator strategy interface</a>
 * @see <a href="DEC-77">DEC-77 D-1/D-2 — flat ranked list; sort semantics</a>
 * @see <a href="DEC-59">DEC-59 — operator-confirmation team-assignment workflow</a>
 * @see <a href="E66S01">E66S01 — AC2, AC4, AC5, AC7</a>
 */
public interface TeamSortCalculator {

    /**
     * Returns the registry key that uniquely identifies this calculator.
     *
     * <p>Known values: {@code "team_number"}, {@code "placement_group"}, {@code "group_placement"}.
     *
     * @return the registry key; never {@code null}
     */
    String getKeyId();

    /**
     * Ranks the teams from the predecessor phase into a flat ordered list.
     *
     * <p>The returned list contains one {@link RankedTeamEntry} per avatar in {@code fromAvatars}.
     * Element at index 0 is the highest-ranked team; the last element is the lowest-ranked.
     *
     * <p>The caller is responsible for loading the {@code ratingsByAvatarId} map before calling
     * this method (via {@link TeamAvatarRatingRepository#findByPhaseId(UUID)} for Phase N). An
     * empty map signals that no ratings exist for the previous phase.
     *
     * <p>No {@code teamId} is written by any implementation (DEC-59 Clause C / AC7 — teamId is
     * written only by the operator-confirmation handler). The returned entries carry the teamId
     * from the source avatar so the caller can build a {@link TeamAvatarProposal}.
     *
     * @param fromAvatars the previous phase's {@link TeamAvatar} slots; must not be {@code null};
     *     sorted by (groupNumber, groupPosition) ascending (repository contract)
     * @param ratingsByAvatarId map from avatar-id to its {@link TeamAvatarRating}; must not be
     *     {@code null}; may be empty (no ratings available)
     * @param teamById map from teamId to {@link Team} for display-field population; must not be
     *     {@code null}
     * @return flat ranked list, one entry per avatar; never {@code null}; index 0 = highest rank
     * @throws NullPointerException if {@code fromAvatars}, {@code ratingsByAvatarId}, or {@code
     *     teamById} is {@code null}
     */
    List<RankedTeamEntry> rank(
            List<TeamAvatar> fromAvatars,
            Map<UUID, TeamAvatarRating> ratingsByAvatarId,
            Map<UUID, Team> teamById);
}
