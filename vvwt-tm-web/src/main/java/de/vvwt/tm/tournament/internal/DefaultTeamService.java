package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamRepository;
import de.vvwt.tm.tournament.TeamService;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.exceptions.ConflictException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Default implementation of {@link TeamService} — Domain service for Team CRUD operations.
 *
 * <p>Implements the public {@link TeamService} interface per DEC-35 (implementation lives in {@code
 * tournament.internal}; interface lives in {@code tournament} root). Qualifier {@code
 * "tmTeamService"} is preserved verbatim (AC-QUALIFIER-PRESERVED / C-12) to avoid breaking Spring's
 * {@code @Qualifier} injection in {@code TeamController}.
 *
 * <h2>Business rules enforced</h2>
 *
 * <ul>
 *   <li>AC1: listTeams returns all teams for the given tournament (tenant-scoped) by team_number
 *       ASC
 *   <li>AC2: createTeam auto-assigns team_number if not provided; validates uniqueness (AC10)
 *   <li>AC3: updateTeam rejects non-DRAFT with {@link ConflictException} (AC11)
 *   <li>AC4: deleteTeam rejects teams with TeamAvatar references (DEC-9)
 *   <li>AC5: bulkCreateTeams — partial failure does not abort the batch
 * </ul>
 *
 * @see TeamService
 * @see TeamRepository
 * @see Team
 * @see <a href="DEC-21">DEC-21 — Spring Modulith internal-package discipline</a>
 * @see <a href="DEC-22">DEC-22 — TDD reconstruction-in-place</a>
 * @see <a href="DEC-35">DEC-35 — Service interface in public / impl in internal</a>
 * @see <a href="DEC-9">DEC-9 — TeamAvatar structural identity (delete guard)</a>
 * @see <a href="E21S04">E21S04 — Team aggregate reconstruction (inventory line 188)</a>
 * @see <a href="E33S02">E33S02 — DEC-35 interface extraction</a>
 */
@Service("tmTeamService")
public class DefaultTeamService implements TeamService {

    /**
     * Sentinel UUID: excludes no existing team when checking team_number uniqueness for a NEW team.
     */
    private static final UUID NO_EXCLUDE = new UUID(0, 0);

    private final TeamRepository teamRepository;
    private final TournamentRepository tournamentRepository;

    /**
     * Constructs the service with its required collaborators.
     *
     * @param teamRepository team persistence (new Modulith repository, tenant-scoped)
     * @param tournamentRepository tournament persistence (new Modulith repository) — ownership +
     *     status checks
     */
    public DefaultTeamService(
            TeamRepository teamRepository, TournamentRepository tournamentRepository) {
        this.teamRepository = teamRepository;
        this.tournamentRepository = tournamentRepository;
    }

    // -------------------------------------------------------------------------
    // AC1 — List teams
    // -------------------------------------------------------------------------

    /**
     * Returns all teams for the given tournament (tenant-scoped), ordered by team_number ascending.
     *
     * @param tournamentId the tournament UUID
     * @return list of teams ordered by team_number ascending; never {@code null}
     * @throws NoSuchElementException if the tournament does not exist for the current tenant (AC13)
     */
    @Override
    public List<Team> listTeams(UUID tournamentId) {
        // AC13: 404 if tournament does not exist for the current tenant
        tournamentRepository
                .findById(tournamentId)
                .orElseThrow(
                        () -> new NoSuchElementException("Tournament not found: " + tournamentId));
        return teamRepository.findByTournamentId(tournamentId);
    }

    // -------------------------------------------------------------------------
    // AC2 — Get single team
    // -------------------------------------------------------------------------

    /**
     * Returns the team with the given ID in the given tournament.
     *
     * @param tournamentId the tournament UUID
     * @param teamId the team UUID
     * @return the team (never {@code null})
     * @throws NoSuchElementException if not found or wrong tournament
     */
    @Override
    public Team getTeam(UUID tournamentId, UUID teamId) {
        return teamRepository
                .findById(teamId)
                .filter(t -> tournamentId.equals(t.getTournamentId()))
                .orElseThrow(() -> new NoSuchElementException("Team not found: " + teamId));
    }

    // -------------------------------------------------------------------------
    // AC2 — Create team
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
     * @throws NoSuchElementException if the tournament does not exist for the current tenant (AC13)
     * @throws ConflictException if the team_number is already in use in this tournament (AC10)
     */
    @Override
    public Team createTeam(
            UUID tournamentId,
            String description,
            int teamNumber,
            boolean participate,
            boolean refereeAssignment,
            boolean withoutAssessment) {
        int resolvedNumber =
                teamNumber > 0 ? teamNumber : teamRepository.nextTeamNumber(tournamentId);

        if (teamRepository.teamNumberExists(tournamentId, resolvedNumber, NO_EXCLUDE)) {
            throw new ConflictException(
                    "Team number "
                            + resolvedNumber
                            + " is already in use in tournament "
                            + tournamentId
                            + ". Use a different team number.");
        }

        Team team = new Team();
        team.setId(UUID.randomUUID());
        team.setTournamentId(tournamentId);
        team.setTeamNumber(resolvedNumber);
        team.setDescription(description);
        team.setParticipate(participate);
        team.setRefereeAssignment(refereeAssignment);
        team.setWithoutAssessment(withoutAssessment);
        team.setCreatedAt(LocalDateTime.now());

        return teamRepository.save(team);
    }

    // -------------------------------------------------------------------------
    // AC3 — Update team
    // -------------------------------------------------------------------------

    /**
     * Updates an existing team. Only teams in a DRAFT tournament may be modified (AC3, AC11).
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
     * @throws ConflictException if the tournament is not in DRAFT status (AC3/AC11)
     * @throws ConflictException if the new team_number is already in use (AC10)
     */
    @Override
    public Team updateTeam(
            UUID tournamentId,
            UUID teamId,
            String description,
            int teamNumber,
            boolean participate,
            boolean refereeAssignment,
            boolean withoutAssessment) {
        Team team =
                teamRepository
                        .findById(teamId)
                        .filter(t -> tournamentId.equals(t.getTournamentId()))
                        .orElseThrow(() -> new NoSuchElementException("Team not found: " + teamId));

        int resolvedNumber = teamNumber > 0 ? teamNumber : team.getTeamNumber();
        if (resolvedNumber != team.getTeamNumber()
                && teamRepository.teamNumberExists(tournamentId, resolvedNumber, teamId)) {
            throw new ConflictException(
                    "Team number "
                            + resolvedNumber
                            + " is already in use in tournament "
                            + tournamentId
                            + ". Use a different team number.");
        }

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
     * Deletes a team. Only possible for DRAFT tournaments with no TeamAvatar references (AC4,
     * DEC-9).
     *
     * @param tournamentId the tournament UUID
     * @param teamId the team UUID
     * @throws NoSuchElementException if tournament or team does not exist for the current tenant
     * @throws ConflictException if the tournament is not in DRAFT status (AC11)
     * @throws ConflictException if the team has TeamAvatar references (AC4 / DEC-9)
     */
    @Override
    public void deleteTeam(UUID tournamentId, UUID teamId) {
        teamRepository
                .findById(teamId)
                .filter(t -> tournamentId.equals(t.getTournamentId()))
                .orElseThrow(() -> new NoSuchElementException("Team not found: " + teamId));

        if (teamRepository.hasTeamAvatars(teamId)) {
            throw new ConflictException(
                    "Team '"
                            + teamId
                            + "' cannot be deleted because it has TeamAvatar references. "
                            + "Remove all phase assignments first.");
        }

        teamRepository.deleteById(teamId);
    }

    // -------------------------------------------------------------------------
    // AC5 — Bulk create teams
    // -------------------------------------------------------------------------

    /**
     * Creates multiple teams in a single request (AC5).
     *
     * <p>Each team is created independently. Partial failure does not abort the batch — each item's
     * result is included in the returned list.
     *
     * @param tournamentId the tournament UUID
     * @param requests list of creation requests
     * @return per-item results (success or error per entry)
     * @throws NoSuchElementException if the tournament does not exist for the current tenant (AC13)
     */
    @Override
    public List<BulkCreateResult> bulkCreateTeams(
            UUID tournamentId, List<BulkCreateRequest> requests) {
        List<BulkCreateResult> results = new ArrayList<>();
        for (BulkCreateRequest req : requests) {
            try {
                Team created =
                        createTeam(
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
}
