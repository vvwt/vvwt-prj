package de.vvwt.tm.tournament;

import de.vvwt.tm.tournament.internal.TournamentService;
import de.vvwt.tm.tournament.internal.dto.TournamentCreateRequest;
import de.vvwt.tm.tournament.internal.dto.TournamentResponse;
import de.vvwt.tm.tournament.internal.dto.TournamentUpdateRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
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
 * REST controller for Tournament CRUD operations — reconstruction-in-place target (DEC-21/DEC-22).
 *
 * <p>Mirrors {@code de.vvwt.tm.infrastructure.web.TournamentController} but lives at the Modulith
 * target package {@code de.vvwt.tm.tournament} (public API surface per DEC-21 §Module layout).
 * Uses {@code /api/tm/tournaments} mapping to avoid {@code RequestMappingHandlerMapping} ambiguity
 * with the legacy {@code /api/tournaments} controller during reconstruction-in-place. The mapping
 * will be normalized to {@code /api/tournaments} at the E21S13 atomic cutover.
 *
 * <h2>Endpoints</h2>
 *
 * <ul>
 *   <li>GET /api/tm/tournaments — list all (AC1)
 *   <li>GET /api/tm/tournaments/{id} — get one (AC2)
 *   <li>POST /api/tm/tournaments — create new in DRAFT (AC3)
 *   <li>PUT /api/tm/tournaments/{id} — update DRAFT only (AC4)
 *   <li>DELETE /api/tm/tournaments/{id} — delete DRAFT with no phases (AC5)
 * </ul>
 *
 * @see TournamentService
 * @see TournamentResponse
 * @see <a href="DEC-21">DEC-21 — Spring Modulith, root package = public API surface</a>
 * @see <a href="DEC-22">DEC-22 — TDD reconstruction-in-place</a>
 * @see <a href="E21S02">E21S02 — Tournament aggregate reconstruction</a>
 */
@RestController("tmTournamentController")
@RequestMapping("/api/tm/tournaments")
public class TournamentController {

    private final TournamentService tournamentService;

    public TournamentController(
            @Qualifier("tmTournamentService") TournamentService tournamentService) {
        this.tournamentService = tournamentService;
    }

    // -------------------------------------------------------------------------
    // AC1 — GET /api/tm/tournaments
    // -------------------------------------------------------------------------

    /**
     * Returns all tournaments for the current tenant, ordered by {@code created_at} descending.
     *
     * @return 200 OK with a JSON array of tournament summaries
     */
    @GetMapping
    public ResponseEntity<List<TournamentResponse>> listTournaments() {
        List<TournamentResponse> responses =
                tournamentService.listTournaments().stream().map(TournamentResponse::from).toList();
        return ResponseEntity.ok(responses);
    }

    // -------------------------------------------------------------------------
    // AC2 — GET /api/tm/tournaments/{id}
    // -------------------------------------------------------------------------

    /**
     * Returns a single tournament.
     *
     * @param id the tournament UUID (from path)
     * @return 200 OK with the tournament, or 404 if not found or belongs to a different tenant
     */
    @GetMapping("/{id}")
    public ResponseEntity<TournamentResponse> getTournament(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(TournamentResponse.from(tournamentService.getTournament(id)));
    }

    // -------------------------------------------------------------------------
    // AC3 — POST /api/tm/tournaments
    // -------------------------------------------------------------------------

    /**
     * Creates a new tournament in DRAFT status.
     *
     * @param request the tournament creation request body (validated via {@link Valid})
     * @return 201 Created with the new tournament and a {@code Location} header
     */
    @PostMapping
    public ResponseEntity<TournamentResponse> createTournament(
            @RequestBody @Valid TournamentCreateRequest request) {

        var tournament =
                tournamentService.createTournament(
                        request.description(),
                        request.appointment(),
                        request.teamCount(),
                        request.fieldCount(),
                        request.matchFormat(),
                        request.scoringRuleId(),
                        request.setValidationRuleId(),
                        request.matchGeneratorId());

        URI location =
                ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{id}")
                        .buildAndExpand(tournament.getId())
                        .toUri();

        return ResponseEntity.created(location).body(TournamentResponse.from(tournament));
    }

    // -------------------------------------------------------------------------
    // AC4 — PUT /api/tm/tournaments/{id}
    // -------------------------------------------------------------------------

    /**
     * Updates a tournament. Only DRAFT tournaments may be edited.
     *
     * @param id the tournament UUID (from path)
     * @param request the update request body; null fields mean "no change"
     * @return 200 OK with the updated tournament, 404 if not found, 409 if not in DRAFT
     */
    @PutMapping("/{id}")
    public ResponseEntity<TournamentResponse> updateTournament(
            @PathVariable("id") UUID id, @RequestBody @Valid TournamentUpdateRequest request) {

        var tournament =
                tournamentService.updateTournament(
                        id,
                        request.description(),
                        request.appointment(),
                        request.teamCount() != null ? request.teamCount() : 0,
                        request.fieldCount() != null ? request.fieldCount() : 0,
                        request.matchFormat(),
                        request.scoringRuleId(),
                        request.setValidationRuleId(),
                        request.matchGeneratorId(),
                        request.plannedStartTime());

        return ResponseEntity.ok(TournamentResponse.from(tournament));
    }

    // -------------------------------------------------------------------------
    // AC5 — DELETE /api/tm/tournaments/{id}
    // -------------------------------------------------------------------------

    /**
     * Deletes a tournament. Only DRAFT tournaments with no associated phases may be deleted.
     *
     * @param id the tournament UUID (from path)
     * @return 204 No Content on success, 404 if not found, 409 if ACTIVE or has phases
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTournament(@PathVariable("id") UUID id) {
        tournamentService.deleteTournament(id);
        return ResponseEntity.noContent().build();
    }
}
