package de.vvwt.tm.web;

import de.vvwt.tm.tournament.PhaseOverviewResponse;
import de.vvwt.tm.tournament.PhaseQueryService;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the read-only phases overview endpoint (E48S05, DEC-40 Clause A).
 *
 * <h2>Endpoints</h2>
 *
 * <ul>
 *   <li>GET /api/tournaments/{tournamentId}/phases — list phases with match counts
 *       (AC-IMPL-PHASE-LIST-ENDPOINT)
 * </ul>
 *
 * <h2>Security</h2>
 *
 * <p>Endpoint requires authenticated Admin (AC-SECURITY-PHASES-ENDPOINT-AUTH). Secured via the
 * global {@code SecurityFilterChain} (Spring Security HTTP Basic). Anonymous requests → 401.
 *
 * <h2>Read-only contract (AC-NO-WRITES-IN-THIS-STORY)</h2>
 *
 * <p>This controller exposes ONLY GET endpoints. No POST/PUT/DELETE methods exist or may be added
 * in this story. Phase lifecycle mutations are in scope for E48S06.
 *
 * <h2>DTO placement (DEC-40 Clause B Pattern A)</h2>
 *
 * <p>{@link PhaseOverviewResponse} is bounded-context-owned (at {@code de.vvwt.tm.tournament.*}).
 * The controller serializes it directly via Jackson — no web-tier DTO wrapper.
 *
 * @see PhaseQueryService
 * @see PhaseOverviewResponse
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation; Pattern A bounded-context DTO</a>
 * @see <a href="DEC-44">DEC-44 — web-module ITs use @SpringBootTest(RANDOM_PORT)</a>
 * @see <a href="E48S05">E48S05 — AC-IMPL-PHASE-LIST-ENDPOINT</a>
 */
@RestController("tmTournamentPhasesController")
@RequestMapping("/api/tournaments/{tournamentId}/phases")
public class TournamentPhasesController {

    private final PhaseQueryService phaseQueryService;

    public TournamentPhasesController(
            @Qualifier("tmPhaseQueryService") PhaseQueryService phaseQueryService) {
        this.phaseQueryService = phaseQueryService;
    }

    // -------------------------------------------------------------------------
    // GET /api/tournaments/{tournamentId}/phases
    // (AC-IMPL-PHASE-LIST-ENDPOINT, AC-TEST-PHASE-LIST-ENDPOINT-RED)

    /**
     * Returns all phases of a tournament with their status, gameMode, lap number, and match counts
     * grouped by state.
     *
     * <p>Returns 200 with an empty array when the tournament exists but has no phases yet. Returns
     * 404 when no tournament with the given id exists.
     *
     * @param tournamentId the tournament UUID (path variable)
     * @return 200 OK with list of {@link PhaseOverviewResponse}; 404 when tournament not found
     */
    @GetMapping
    public ResponseEntity<List<PhaseOverviewResponse>> listPhases(@PathVariable UUID tournamentId) {
        if (!phaseQueryService.tournamentExists(tournamentId)) {
            return ResponseEntity.notFound().build();
        }
        List<PhaseOverviewResponse> phases = phaseQueryService.listPhasesWithCounts(tournamentId);
        return ResponseEntity.ok(phases);
    }
}
