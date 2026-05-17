// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Strategy interface for sorting/ranking teams into a Phase-N+1 proposal list.
 *
 * <p>Each implementation encodes one of the three team-sort modes ({@code "team_number"}, {@code
 * "placement_group"}, {@code "group_placement"}) that govern how Phase-2+ team-assignment proposals
 * are computed from the previous phase's team-avatar slots and their ratings.
 *
 * <p>The calculator receives all information it needs as parameters. It does NOT call any
 * repository itself (DEC-59 Clause C / AC10 — {@code teamId} is written only by the
 * operator-confirmation handler; the proposal computation merely proposes structural slots).
 *
 * <p>DEC-58 Clause A + DEC-72: public interface in the bounded-context root package {@code
 * de.vvwt.tm.tournament}; implementations live in {@code de.vvwt.tm.tournament.internal} (DEC-35).
 *
 * @see TeamSortCalculatorRegistry
 * @see AbstractAssignmentProposalCalculator
 * @see <a href="DEC-35">DEC-35 — interface in public package, impl in .internal</a>
 * @see <a href="DEC-58">DEC-58 — universal interface mandate</a>
 * @see <a href="DEC-73">DEC-73 D-3 — TeamSortCalculator strategy interface</a>
 * @see <a href="DEC-59">DEC-59 — operator-confirmation team-assignment workflow (behaviour
 *     preserved)</a>
 * @see <a href="E58S03">E58S03 — AC1, AC4, AC5</a>
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
     * Computes a sorted list of {@link TeamAvatarProposal} records from the given avatar slots.
     *
     * <p>The caller is responsible for loading the {@code ratingsByAvatarId} map before calling
     * this method (via {@link TeamAvatarRatingRepository#findByPhaseId(UUID)} for Phase N). An
     * empty map signals that no ratings exist for the previous phase.
     *
     * <p>The returned proposals describe the target slot for each team in Phase N+1. No {@code
     * teamId} is written by any implementation (DEC-59 Clause C — teamId is written only by the
     * operator-confirmation handler, AC10).
     *
     * @param fromAvatars the previous phase's {@link TeamAvatar} slots; must not be {@code null};
     *     sorted by (groupNumber, groupPosition) ascending (repository contract)
     * @param ratingsByAvatarId map from avatar-id to its {@link TeamAvatarRating}; must not be
     *     {@code null}; may be empty (no ratings available)
     * @param teamById map from teamId to {@link Team} for display-field population; must not be
     *     {@code null}
     * @param groupCount number of target groups for the next phase; must be ≥ 1
     * @param sortType the sortType string from the target phase's {@link
     *     de.vvwt.tm.tournament.draft.DraftSection} (populated into each resulting proposal)
     * @return list of proposals, one per avatar slot (Phase 2+ branch); never {@code null}
     * @throws NullPointerException if {@code fromAvatars}, {@code ratingsByAvatarId}, or {@code
     *     teamById} is {@code null}
     * @throws IllegalArgumentException if {@code groupCount} is less than 1
     */
    List<TeamAvatarProposal> sortTeams(
            List<TeamAvatar> fromAvatars,
            Map<UUID, TeamAvatarRating> ratingsByAvatarId,
            Map<UUID, Team> teamById,
            int groupCount,
            String sortType);
}
