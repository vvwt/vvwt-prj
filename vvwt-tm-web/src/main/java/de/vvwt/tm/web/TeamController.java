package de.vvwt.tm.web;

import de.vvwt.tm.photo.PhotoStorageService;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamService;
import de.vvwt.tm.tournament.TeamService.BulkCreateResult;
import de.vvwt.tm.tournament.internal.dto.TeamBulkCreateRequest;
import de.vvwt.tm.tournament.internal.dto.TeamBulkCreateResponse;
import de.vvwt.tm.tournament.internal.dto.TeamCreateRequest;
import de.vvwt.tm.tournament.internal.dto.TeamResponse;
import de.vvwt.tm.tournament.internal.dto.TeamUpdateRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
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
 * REST controller for Team CRUD operations — primary-adapter-isolation target (DEC-40 Clause A).
 *
 * <p>Relocated whole-class from {@code de.vvwt.tm.tournament.TeamController} to {@code
 * de.vvwt.tm.web} per DEC-40 Clause D (Q-1b whole-class relocation, DEC-22 §refactor-clause). URL
 * mappings and JSON wire format preserved byte-equivalent (C-14). {@code @Qualifier} preserved
 * verbatim (C-12).
 *
 * <h2>Endpoints</h2>
 *
 * <ul>
 *   <li>GET /api/tournaments/{tournamentId}/teams — list all
 *   <li>POST /api/tournaments/{tournamentId}/teams — create new
 *   <li>POST /api/tournaments/{tournamentId}/teams/bulk — bulk create
 *   <li>PUT /api/tournaments/{tournamentId}/teams/{id} — update
 *   <li>DELETE /api/tournaments/{tournamentId}/teams/{id} — delete
 * </ul>
 *
 * @see TeamService
 * @see TeamResponse
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation</a>
 * @see <a href="DEC-22">DEC-22 — TDD reconstruction-in-place</a>
 * @see <a href="E22S07">E22S07 — Relocate TeamController to de.vvwt.tm.web</a>
 */
@RestController("tmTeamController")
@RequestMapping("/api/tournaments/{tournamentId}/teams")
public class TeamController {

    private final TeamService teamService;
    private final PhotoStorageService photoStorageService;

    public TeamController(
            @Qualifier("tmTeamService") TeamService teamService,
            PhotoStorageService photoStorageService) {
        this.teamService = teamService;
        this.photoStorageService = photoStorageService;
    }

    // -------------------------------------------------------------------------
    // GET /api/tournaments/{tournamentId}/teams
    // -------------------------------------------------------------------------

    /**
     * Returns all teams for the given tournament, ordered by {@code team_number} ascending.
     *
     * @param tournamentId the tournament UUID (from path)
     * @return 200 OK with a JSON array of teams
     */
    @GetMapping
    public ResponseEntity<List<TeamResponse>> listTeams(
            @PathVariable("tournamentId") UUID tournamentId) {
        List<TeamResponse> responses =
                teamService.listTeams(tournamentId).stream()
                        .map(
                                t ->
                                        TeamResponse.from(
                                                t,
                                                photoStorageService.hasPhoto(
                                                        tournamentId, t.getId())))
                        .toList();
        return ResponseEntity.ok(responses);
    }

    // -------------------------------------------------------------------------
    // POST /api/tournaments/{tournamentId}/teams
    // -------------------------------------------------------------------------

    /**
     * Creates a new team within the given tournament.
     *
     * @param tournamentId the tournament UUID (from path)
     * @param request the team creation request body (validated via {@link Valid})
     * @return 201 Created with the new team and a {@code Location} header
     */
    @PostMapping
    public ResponseEntity<TeamResponse> createTeam(
            @PathVariable("tournamentId") UUID tournamentId,
            @RequestBody @Valid TeamCreateRequest request) {

        Team created =
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
                        .buildAndExpand(created.getId())
                        .toUri();

        return ResponseEntity.created(location).body(TeamResponse.from(created));
    }

    // -------------------------------------------------------------------------
    // POST /api/tournaments/{tournamentId}/teams/bulk
    // -------------------------------------------------------------------------

    /**
     * Bulk-creates teams within the given tournament. Each item is processed independently;
     * failures are reported per-item without rolling back successful items.
     *
     * @param tournamentId the tournament UUID (from path)
     * @param request the bulk create request body (validated via {@link Valid})
     * @return 200 OK with per-item results
     */
    @PostMapping("/bulk")
    public ResponseEntity<TeamBulkCreateResponse> bulkCreateTeams(
            @PathVariable("tournamentId") UUID tournamentId,
            @RequestBody @Valid TeamBulkCreateRequest request) {

        List<TeamService.BulkCreateRequest> serviceRequests =
                request.teams().stream()
                        .map(
                                r ->
                                        new TeamService.BulkCreateRequest(
                                                r.description(),
                                                r.resolvedTeamNumber(),
                                                r.resolvedParticipate(),
                                                r.resolvedRefereeAssignment(),
                                                r.resolvedWithoutAssessment()))
                        .toList();

        List<BulkCreateResult> results = teamService.bulkCreateTeams(tournamentId, serviceRequests);
        return ResponseEntity.status(HttpStatus.CREATED).body(TeamBulkCreateResponse.from(results));
    }

    // -------------------------------------------------------------------------
    // PUT /api/tournaments/{tournamentId}/teams/{id}
    // -------------------------------------------------------------------------

    /**
     * Updates an existing team.
     *
     * @param tournamentId the tournament UUID (from path)
     * @param id the team UUID (from path)
     * @param request the update request body; null fields mean "no change"
     * @return 200 OK with the updated team, 404 if not found
     */
    @PutMapping("/{id}")
    public ResponseEntity<TeamResponse> updateTeam(
            @PathVariable("tournamentId") UUID tournamentId,
            @PathVariable("id") UUID id,
            @RequestBody @Valid TeamUpdateRequest request) {

        Team updated =
                teamService.updateTeam(
                        tournamentId,
                        id,
                        request.description(),
                        request.resolvedTeamNumber(),
                        request.resolvedParticipate(),
                        request.resolvedRefereeAssignment(),
                        request.resolvedWithoutAssessment());

        return ResponseEntity.ok(TeamResponse.from(updated));
    }

    // -------------------------------------------------------------------------
    // DELETE /api/tournaments/{tournamentId}/teams/{id}
    // -------------------------------------------------------------------------

    /**
     * Deletes a team.
     *
     * @param tournamentId the tournament UUID (from path)
     * @param id the team UUID (from path)
     * @return 204 No Content on success, 404 if not found
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTeam(
            @PathVariable("tournamentId") UUID tournamentId, @PathVariable("id") UUID id) {
        teamService.deleteTeam(tournamentId, id);
        return ResponseEntity.noContent().build();
    }
}
