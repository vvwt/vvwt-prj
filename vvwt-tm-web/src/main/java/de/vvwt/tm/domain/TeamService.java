package de.vvwt.tm.domain;

import de.vvwt.tm.domain.repo.TeamRepository;
import de.vvwt.tm.domain.repo.TournamentRepository;
import de.vvwt.tm.infrastructure.web.ConflictException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Domain service for Team CRUD operations (E05S05).
 *
 * <h2>Business rules enforced</h2>
 * <ul>
 *   <li>AC3: Only teams in DRAFT tournaments may be updated</li>
 *   <li>AC4: Only teams in DRAFT tournaments with no TeamAvatar references may be deleted</li>
 *   <li>AC10: team_number must be unique within a tournament (409 on duplicate)</li>
 *   <li>AC11: modification operations on non-DRAFT tournaments return 409</li>
 *   <li>AC13: tournament ownership (tenant scope) is enforced by TournamentRepository</li>
 * </ul>
 *
 * <h2>Tenant scoping</h2>
 * <p>All repository calls are tenant-scoped via the {@link de.vvwt.tm.domain.repo.TenantContext}
 * ThreadLocal, resolved by {@link de.vvwt.tm.domain.repo.DefaultTenantContextResolver} for
 * each HTTP request (DEC-5, DEC-17).
 *
 * @see <a href="../../../../../../.gaai/project/contexts/artefacts/stories/E05S05.story.md">Story E05S05</a>
 */
@Service
public class TeamService {

    /** Sentinel UUID used to exclude no existing team when checking for number uniqueness. */
    private static final UUID NO_EXCLUDE = new UUID(0, 0);

    private final TeamRepository teamRepository;
    private final TournamentRepository tournamentRepository;

    /**
     * Constructs the service with its required collaborators.
     *
     * @param teamRepository       team persistence (tenant-scoped)
     * @param tournamentRepository tournament persistence — used for ownership + status checks
     */
    public TeamService(TeamRepository teamRepository,
                       TournamentRepository tournamentRepository) {
        this.teamRepository = teamRepository;
        this.tournamentRepository = tournamentRepository;
    }

    // -------------------------------------------------------------------------
    // AC1 — List teams
    // -------------------------------------------------------------------------

    /**
     * Returns all teams for the given tournament (tenant-scoped), ordered by team_number ascending.
     *
     * <p>The tournament ownership check (AC13) is implicit: if the tournamentId belongs to a
     * different tenant, {@link TournamentRepository#findById} returns empty → 404.
     *
     * @param tournamentId the tournament UUID
     * @return list of teams ordered by team_number ascending; never {@code null}
     * @throws NoSuchElementException if the tournament does not exist for the current tenant
     * @throws IllegalStateException  if no tenant context is active
     */
    public List<Team> listTeams(UUID tournamentId) {
        // Ownership check (AC13): resolves to 404 for cross-tenant access
        getTournamentOrThrow(tournamentId);
        return teamRepository.findByTournamentId(tournamentId);
    }

    // -------------------------------------------------------------------------
    // AC2 — Create team
    // -------------------------------------------------------------------------

    /**
     * Creates a new team in the given tournament.
     *
     * <p>If {@code teamNumber} is zero or negative, it is auto-assigned as max(existing) + 1.
     *
     * @param tournamentId      the tournament UUID
     * @param description       team name (required, not blank)
     * @param teamNumber        team number (auto-assigned if ≤ 0)
     * @param participate       whether the team participates (default: true)
     * @param refereeAssignment whether the team provides a referee (default: false)
     * @param withoutAssessment whether the team is excluded from standings (default: false)
     * @return the persisted team (never {@code null})
     * @throws NoSuchElementException if the tournament does not exist for the current tenant
     * @throws ConflictException      if the team_number is already in use in this tournament (AC10)
     * @throws IllegalStateException  if no tenant context is active
     */
    public Team createTeam(UUID tournamentId,
                            String description,
                            int teamNumber,
                            boolean participate,
                            boolean refereeAssignment,
                            boolean withoutAssessment) {
        // AC13: tournament ownership check
        getTournamentOrThrow(tournamentId);

        // AC2: auto-assign team number if not provided
        int resolvedNumber = teamNumber > 0
                ? teamNumber
                : teamRepository.nextTeamNumber(tournamentId);

        // AC10: duplicate team number check
        if (teamRepository.teamNumberExists(tournamentId, resolvedNumber, NO_EXCLUDE)) {
            throw new ConflictException(
                    "Team number " + resolvedNumber + " is already in use in tournament "
                    + tournamentId + ". Use a different team number.");
        }

        Team team = new Team(
                UUID.randomUUID(),
                null,   // tenantId set by TenantScopedRepository.save()
                tournamentId,
                resolvedNumber,
                description,
                participate,
                refereeAssignment,
                withoutAssessment,
                LocalDateTime.now());

        return teamRepository.save(team);
    }

    // -------------------------------------------------------------------------
    // AC3 — Update team
    // -------------------------------------------------------------------------

    /**
     * Updates an existing team. Only teams in a DRAFT tournament may be modified (AC3, AC11).
     *
     * @param tournamentId      the tournament UUID
     * @param teamId            the team UUID
     * @param description       new description (applied if not {@code null})
     * @param teamNumber        new team number (applied if &gt; 0; 0 = no change)
     * @param participate       new participate flag
     * @param refereeAssignment new refereeAssignment flag
     * @param withoutAssessment new withoutAssessment flag
     * @return the updated team (never {@code null})
     * @throws NoSuchElementException if tournament or team does not exist for the current tenant
     * @throws ConflictException      if the tournament is not in DRAFT status (AC3/AC11)
     * @throws ConflictException      if the new team_number is already in use (AC10)
     * @throws IllegalStateException  if no tenant context is active
     */
    public Team updateTeam(UUID tournamentId,
                            UUID teamId,
                            String description,
                            int teamNumber,
                            boolean participate,
                            boolean refereeAssignment,
                            boolean withoutAssessment) {
        // AC13: tournament ownership + status check
        Tournament tournament = getTournamentOrThrow(tournamentId);
        requireDraft(tournament);

        // Fetch team (tenant-scoped, implicitly checks ownership)
        Team team = teamRepository.findById(teamId)
                .filter(t -> tournamentId.equals(t.getTournamentId()))
                .orElseThrow(() -> new NoSuchElementException("Team not found: " + teamId));

        // AC10: team number duplicate check (exclude current team from count)
        int resolvedNumber = teamNumber > 0 ? teamNumber : team.getTeamNumber();
        if (resolvedNumber != team.getTeamNumber()
                && teamRepository.teamNumberExists(tournamentId, resolvedNumber, teamId)) {
            throw new ConflictException(
                    "Team number " + resolvedNumber + " is already in use in tournament "
                    + tournamentId + ". Use a different team number.");
        }

        // Apply updates
        if (description != null) {
            team.setDescription(description);
        }
        team.setTeamNumber(resolvedNumber);
        team.setParticipate(participate);
        team.setRefereeAssignment(refereeAssignment);
        team.setWithoutAssessment(withoutAssessment);

        return teamRepository.save(team);
    }

    // -------------------------------------------------------------------------
    // AC4 — Delete team
    // -------------------------------------------------------------------------

    /**
     * Deletes a team. Only possible for DRAFT tournaments with no TeamAvatar references (AC4).
     *
     * @param tournamentId the tournament UUID
     * @param teamId       the team UUID
     * @throws NoSuchElementException if tournament or team does not exist for the current tenant
     * @throws ConflictException      if the tournament is not in DRAFT status (AC11)
     * @throws ConflictException      if the team has TeamAvatar references (AC4 / DEC-9)
     * @throws IllegalStateException  if no tenant context is active
     */
    public void deleteTeam(UUID tournamentId, UUID teamId) {
        // AC13: tournament ownership + status check
        Tournament tournament = getTournamentOrThrow(tournamentId);
        requireDraft(tournament);

        // Fetch team (tenant-scoped)
        teamRepository.findById(teamId)
                .filter(t -> tournamentId.equals(t.getTournamentId()))
                .orElseThrow(() -> new NoSuchElementException("Team not found: " + teamId));

        // AC4: no TeamAvatar references allowed before deletion (DEC-9)
        if (teamRepository.hasTeamAvatars(teamId)) {
            throw new ConflictException(
                    "Team '" + teamId + "' cannot be deleted because it has TeamAvatar references. "
                    + "Remove all phase assignments first.");
        }

        teamRepository.deleteById(teamId);
    }

    // -------------------------------------------------------------------------
    // AC5 — Bulk create teams
    // -------------------------------------------------------------------------

    /**
     * Creates multiple teams in a single transaction (AC5).
     *
     * <p>Each team in the request is validated individually. If any team has a duplicate
     * team_number (within the batch or against existing teams), that team's result
     * carries an error — the successfully created teams are returned in the same response.
     *
     * <p>This method uses a best-effort strategy: it attempts to create each team and
     * collects results per-item. Caller receives a list of {@link BulkCreateResult} entries.
     *
     * @param tournamentId the tournament UUID
     * @param requests     list of team creation requests
     * @return list of per-item results (success or error per entry)
     * @throws NoSuchElementException if the tournament does not exist for the current tenant
     * @throws IllegalStateException  if no tenant context is active
     */
    public List<BulkCreateResult> bulkCreateTeams(UUID tournamentId,
                                                    List<BulkCreateRequest> requests) {
        // AC13: tournament ownership check
        getTournamentOrThrow(tournamentId);

        List<BulkCreateResult> results = new ArrayList<>();
        for (BulkCreateRequest req : requests) {
            try {
                Team created = createTeam(
                        tournamentId,
                        req.description(),
                        req.teamNumber(),
                        req.participate(),
                        req.refereeAssignment(),
                        req.withoutAssessment());
                results.add(BulkCreateResult.success(created));
            } catch (ConflictException | IllegalArgumentException ex) {
                results.add(BulkCreateResult.error(req, ex.getMessage()));
            }
        }
        return results;
    }

    // -------------------------------------------------------------------------
    // Inner types for bulk create (AC5)
    // -------------------------------------------------------------------------

    /**
     * Input record for a single team in a bulk create request (AC5).
     */
    public record BulkCreateRequest(
            String description,
            int teamNumber,
            boolean participate,
            boolean refereeAssignment,
            boolean withoutAssessment
    ) {}

    /**
     * Per-item result of a bulk create operation (AC5).
     *
     * <p>Either {@code team} is set (success) or {@code errorMessage} is set (failure).
     */
    public record BulkCreateResult(
            Team team,
            String errorMessage
    ) {
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

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Loads a tournament (tenant-scoped), throwing {@link NoSuchElementException} if not found.
     * This implicitly enforces AC13 tournament ownership: cross-tenant IDs return empty → 404.
     */
    private Tournament getTournamentOrThrow(UUID tournamentId) {
        return tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Tournament not found: " + tournamentId));
    }

    /**
     * Throws {@link ConflictException} if the tournament is not in DRAFT status (AC3, AC11).
     */
    private void requireDraft(Tournament tournament) {
        if (!"DRAFT".equals(tournament.getStatus())) {
            throw new ConflictException(
                    "Tournament '" + tournament.getId() + "' is " + tournament.getStatus()
                    + ". Teams can only be modified in DRAFT tournaments.");
        }
    }
}
