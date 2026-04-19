package de.vvwt.tm.infrastructure.web;

import de.vvwt.tm.domain.TeamService;
import de.vvwt.tm.domain.photo.PhotoStorageService;
import de.vvwt.tm.infrastructure.web.dto.TeamBulkCreateRequest;
import de.vvwt.tm.infrastructure.web.dto.TeamBulkCreateResponse;
import de.vvwt.tm.infrastructure.web.dto.TeamCreateRequest;
import de.vvwt.tm.infrastructure.web.dto.TeamResponse;
import de.vvwt.tm.infrastructure.web.dto.TeamUpdateRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * REST controller for Team CRUD operations (E05S05).
 *
 * <h2>Endpoints</h2>
 *
 * <ul>
 *   <li>GET /api/tournaments/{tournamentId}/teams — list teams, ordered by team_number (AC1, E12S02
 *       AC4)
 *   <li>POST /api/tournaments/{tournamentId}/teams — create team (AC2)
 *   <li>PUT /api/tournaments/{tournamentId}/teams/{id} — update team (AC3)
 *   <li>DELETE /api/tournaments/{tournamentId}/teams/{id} — delete team (AC4)
 *   <li>POST /api/tournaments/{tournamentId}/teams/bulk — bulk create teams (AC5)
 * </ul>
 *
 * <h2>Error handling</h2>
 *
 * <p>All exceptions are mapped to structured JSON responses by {@link GlobalExceptionHandler}:
 *
 * <ul>
 *   <li>{@link java.util.NoSuchElementException} → 404 (tournament or team not found)
 *   <li>{@link ConflictException} → 409 (status constraint or duplicate team_number)
 *   <li>{@link org.springframework.web.bind.MethodArgumentNotValidException} → 400
 * </ul>
 *
 * <h2>Security (AC13)</h2>
 *
 * <p>All /api/** endpoints require HTTP Basic authentication. Tournament ownership (AC13) is
 * enforced by {@link de.vvwt.tm.domain.TeamService} via tenant-scoped tournament lookup:
 * cross-tenant tournament IDs produce 404, not 403.
 *
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S05.story.md">Story
 *     E05S05</a>
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E12S02.story.md">Story
 *     E12S02 AC4</a>
 */
@RestController
@RequestMapping("/api/tournaments/{tournamentId}/teams")
public class TeamController {

    private final TeamService teamService;
    private final PhotoStorageService photoStorageService;

    public TeamController(TeamService teamService, PhotoStorageService photoStorageService) {
        this.teamService = teamService;
        this.photoStorageService = photoStorageService;
    }

    // -------------------------------------------------------------------------
    // AC1 — GET /api/tournaments/{tournamentId}/teams
    // -------------------------------------------------------------------------

    /**
     * Returns all teams for the given tournament, ordered by team_number ascending.
     *
     * <p>E12S02 AC4: Each team entry includes a {@code hasPhoto} boolean indicating whether a team
     * photo has been uploaded for this team in this tournament.
     *
     * @param tournamentId the tournament UUID (path variable)
     * @return 200 OK with a JSON array; 404 if tournament not found or cross-tenant
     */
    @GetMapping
    public ResponseEntity<List<TeamResponse>> listTeams(
            @PathVariable("tournamentId") UUID tournamentId) {

        List<TeamResponse> responses =
                teamService.listTeams(tournamentId).stream()
                        .map(
                                team ->
                                        TeamResponse.from(
                                                team,
                                                photoStorageService.hasPhoto(
                                                        tournamentId, team.getId())))
                        .toList();
        return ResponseEntity.ok(responses);
    }

    // -------------------------------------------------------------------------
    // AC2 — POST /api/tournaments/{tournamentId}/teams
    // -------------------------------------------------------------------------

    /**
     * Creates a new team in the given tournament.
     *
     * @param tournamentId the tournament UUID (path variable)
     * @param request the team creation request body
     * @return 201 Created with the new team and a {@code Location} header
     */
    @PostMapping
    public ResponseEntity<TeamResponse> createTeam(
            @PathVariable("tournamentId") UUID tournamentId,
            @RequestBody @Valid TeamCreateRequest request) {

        var team =
                teamService.createTeam(
                        tournamentId,
                        request.description(),
                        request.resolvedTeamNumber(),
                        request.resolvedParticipate(),
                        request.resolvedRefereeAssignment(),
                        request.resolvedWithoutAssessment());

        URI location =
                ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{id}")
                        .buildAndExpand(team.getId())
                        .toUri();

        return ResponseEntity.created(location).body(TeamResponse.from(team));
    }

    // -------------------------------------------------------------------------
    // AC3 — PUT /api/tournaments/{tournamentId}/teams/{id}
    // -------------------------------------------------------------------------

    /**
     * Updates a team. Only teams in a DRAFT tournament may be edited (AC3/AC11).
     *
     * @param tournamentId the tournament UUID (path variable)
     * @param id the team UUID (path variable)
     * @param request the update request body
     * @return 200 OK with the updated team; 404 if not found; 409 if not DRAFT or duplicate number
     */
    @PutMapping("/{id}")
    public ResponseEntity<TeamResponse> updateTeam(
            @PathVariable("tournamentId") UUID tournamentId,
            @PathVariable("id") UUID id,
            @RequestBody @Valid TeamUpdateRequest request) {

        var team =
                teamService.updateTeam(
                        tournamentId,
                        id,
                        request.description(),
                        request.resolvedTeamNumber(),
                        request.resolvedParticipate(),
                        request.resolvedRefereeAssignment(),
                        request.resolvedWithoutAssessment());

        return ResponseEntity.ok(TeamResponse.from(team));
    }

    // -------------------------------------------------------------------------
    // AC4 — DELETE /api/tournaments/{tournamentId}/teams/{id}
    // -------------------------------------------------------------------------

    /**
     * Deletes a team. Only DRAFT tournaments with no TeamAvatar references may delete teams (AC4).
     *
     * @param tournamentId the tournament UUID (path variable)
     * @param id the team UUID (path variable)
     * @return 204 No Content on success; 404 if not found; 409 if ACTIVE or has avatars
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTeam(
            @PathVariable("tournamentId") UUID tournamentId, @PathVariable("id") UUID id) {

        teamService.deleteTeam(tournamentId, id);
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // AC5 — POST /api/tournaments/{tournamentId}/teams/bulk
    // -------------------------------------------------------------------------

    /**
     * Bulk creates multiple teams in a single request (AC5).
     *
     * <p>Returns HTTP 200 with per-item results. Individual failures (e.g., duplicate team_number)
     * do not prevent other items from being created.
     *
     * @param tournamentId the tournament UUID (path variable)
     * @param request the bulk creation request body
     * @return 200 OK with per-item results; 404 if tournament not found
     */
    @PostMapping("/bulk")
    public ResponseEntity<TeamBulkCreateResponse> bulkCreateTeams(
            @PathVariable("tournamentId") UUID tournamentId,
            @RequestBody @Valid TeamBulkCreateRequest request) {

        List<TeamService.BulkCreateRequest> domainRequests =
                request.teams().stream()
                        .map(
                                t ->
                                        new TeamService.BulkCreateRequest(
                                                t.description(),
                                                t.resolvedTeamNumber(),
                                                t.resolvedParticipate(),
                                                t.resolvedRefereeAssignment(),
                                                t.resolvedWithoutAssessment()))
                        .collect(Collectors.toList());

        List<TeamService.BulkCreateResult> results =
                teamService.bulkCreateTeams(tournamentId, domainRequests);

        return ResponseEntity.ok(TeamBulkCreateResponse.from(results));
    }
}
