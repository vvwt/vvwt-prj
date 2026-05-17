// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Public service interface for Team CRUD operations in the {@code tournament} Modulith module.
 *
 * <p>Exposes the module's team-management contract as a typed port per DEC-35 (pragmatic hexagonal
 * layout). The sole implementation is {@code de.vvwt.tm.tournament.internal.DefaultTeamService}.
 *
 * <h2>Business rules enforced (by the implementation)</h2>
 *
 * <ul>
 *   <li>listTeams returns all teams for the given tournament (tenant-scoped) by team_number ASC
 *   <li>createTeam auto-assigns team_number if not provided; validates uniqueness
 *   <li>updateTeam rejects non-DRAFT tournaments with {@link
 *       de.vvwt.tm.tournament.exceptions.ConflictException}
 *   <li>deleteTeam rejects teams with TeamAvatar references (DEC-9)
 *   <li>bulkCreateTeams — partial failure does not abort the batch
 * </ul>
 *
 * @see de.vvwt.tm.tournament.internal.DefaultTeamService
 * @see TeamRepository
 * @see Team
 * @see <a href="DEC-35">DEC-35 — Spring Modulith package layout (interface-in-public /
 *     impl-in-internal)</a>
 * @see <a href="DEC-36">DEC-36 — Cross-package tests reference public interface only</a>
 * @see <a href="E33S02">E33S02 — TeamService interface extraction (DEC-35 retrofit)</a>
 */
public interface TeamService {

    // -------------------------------------------------------------------------
    // List teams
    // -------------------------------------------------------------------------

    /**
     * Returns all teams for the given tournament (tenant-scoped), ordered by team_number ascending.
     *
     * @param tournamentId the tournament UUID
     * @return list of teams ordered by team_number ascending; never {@code null}
     * @throws NoSuchElementException if the tournament does not exist for the current tenant
     */
    List<Team> listTeams(UUID tournamentId);

    // -------------------------------------------------------------------------
    // Get single team
    // -------------------------------------------------------------------------

    /**
     * Returns the team with the given ID in the given tournament.
     *
     * @param tournamentId the tournament UUID
     * @param teamId the team UUID
     * @return the team (never {@code null})
     * @throws NoSuchElementException if not found or wrong tournament
     */
    Team getTeam(UUID tournamentId, UUID teamId);

    // -------------------------------------------------------------------------
    // Create team
    // -------------------------------------------------------------------------

    /**
     * Creates a new team in the given tournament.
     *
     * <p>If {@code teamNumber} is zero or negative, it is auto-assigned as max(existing) + 1.
     *
     * @param tournamentId the tournament UUID
     * @param description team name (required)
     * @param teamNumber team number (auto-assigned if ≤ 0)
     * @param participate whether the team participates (default: true)
     * @param refereeAssignment whether the team provides a referee (default: false)
     * @param withoutAssessment whether the team is excluded from standings (default: false)
     * @return the persisted team (never {@code null})
     * @throws NoSuchElementException if the tournament does not exist for the current tenant
     * @throws de.vvwt.tm.tournament.exceptions.ConflictException if the team_number is already in
     *     use in this tournament
     */
    Team createTeam(
            UUID tournamentId,
            String description,
            int teamNumber,
            boolean participate,
            boolean refereeAssignment,
            boolean withoutAssessment);

    // -------------------------------------------------------------------------
    // Update team
    // -------------------------------------------------------------------------

    /**
     * Updates an existing team. Only teams in a DRAFT tournament may be modified.
     *
     * @param tournamentId the tournament UUID
     * @param teamId the team UUID
     * @param description new description (applied if not {@code null})
     * @param teamNumber new team number (applied if &gt; 0; 0 = no change)
     * @param participate new participate flag
     * @param refereeAssignment new refereeAssignment flag
     * @param withoutAssessment new withoutAssessment flag
     * @return the updated team (never {@code null})
     * @throws NoSuchElementException if tournament or team does not exist for the current tenant
     * @throws de.vvwt.tm.tournament.exceptions.ConflictException if the tournament is not in DRAFT
     *     status
     * @throws de.vvwt.tm.tournament.exceptions.ConflictException if the new team_number is already
     *     in use
     */
    Team updateTeam(
            UUID tournamentId,
            UUID teamId,
            String description,
            int teamNumber,
            boolean participate,
            boolean refereeAssignment,
            boolean withoutAssessment);

    // -------------------------------------------------------------------------
    // Delete team
    // -------------------------------------------------------------------------

    /**
     * Deletes a team. Only possible for DRAFT tournaments with no TeamAvatar references (DEC-9).
     *
     * @param tournamentId the tournament UUID
     * @param teamId the team UUID
     * @throws NoSuchElementException if tournament or team does not exist for the current tenant
     * @throws de.vvwt.tm.tournament.exceptions.ConflictException if the tournament is not in DRAFT
     *     status
     * @throws de.vvwt.tm.tournament.exceptions.ConflictException if the team has TeamAvatar
     *     references
     */
    void deleteTeam(UUID tournamentId, UUID teamId);

    // -------------------------------------------------------------------------
    // Bulk create teams
    // -------------------------------------------------------------------------

    /**
     * Creates multiple teams in a single request.
     *
     * <p>Each team is created independently. Partial failure does not abort the batch — each item's
     * result is included in the returned list.
     *
     * @param tournamentId the tournament UUID
     * @param requests list of creation requests
     * @return per-item results (success or error per entry)
     * @throws NoSuchElementException if the tournament does not exist for the current tenant
     */
    List<BulkCreateResult> bulkCreateTeams(UUID tournamentId, List<BulkCreateRequest> requests);

    // -------------------------------------------------------------------------
    // Nested types for bulk create
    // -------------------------------------------------------------------------

    /** Input record for a single team in a bulk create request. */
    record BulkCreateRequest(
            String description,
            int teamNumber,
            boolean participate,
            boolean refereeAssignment,
            boolean withoutAssessment) {}

    /**
     * Per-item result of a bulk create operation.
     *
     * <p>Either {@code team} is set (success) or {@code errorMessage} is set (failure).
     */
    record BulkCreateResult(Team team, String errorMessage) {
        /** Factory — successful creation. */
        public static BulkCreateResult success(Team team) {
            return new BulkCreateResult(team, null);
        }

        /** Factory — creation failed. */
        public static BulkCreateResult error(BulkCreateRequest req, String message) {
            return new BulkCreateResult(null, message);
        }

        /** Returns {@code true} if this item was created successfully. */
        public boolean isSuccess() {
            return team != null;
        }
    }
}
