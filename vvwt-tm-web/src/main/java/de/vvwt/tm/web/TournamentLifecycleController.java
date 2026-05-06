package de.vvwt.tm.web;

import de.vvwt.tm.tournament.TournamentLifecycleService;
import de.vvwt.tm.tournament.internal.dto.TournamentResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for tournament lifecycle status transitions (DEC-40, E48S03).
 *
 * <p>Primary-adapter-isolation: lives in {@code de.vvwt.tm.web} per DEC-40 Clause A. Delegates all
 * business logic to {@link TournamentLifecycleService}.
 *
 * <h2>Endpoints</h2>
 *
 * <ul>
 *   <li>POST /api/tournaments/{id}/mark-planned — DRAFT → PLANNED
 *   <li>POST /api/tournaments/{id}/activate — PLANNED → ACTIVE
 *   <li>POST /api/tournaments/{id}/complete — ACTIVE → COMPLETED
 *   <li>POST /api/tournaments/{id}/cancel — PLANNED or ACTIVE → CANCELLED
 * </ul>
 *
 * <p>Invalid transitions throw {@link de.vvwt.tm.tournament.exceptions.ConflictException}, which is
 * mapped to HTTP 409 by {@code GlobalExceptionHandler}.
 *
 * @see TournamentLifecycleService
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation</a>
 * @see <a href="DEC-44">DEC-44 — web-module ITs use @SpringBootTest(RANDOM_PORT)</a>
 * @see <a href="E48S03">E48S03 — Tournament lifecycle transitions</a>
 */
@RestController
@RequestMapping("/api/tournaments")
public class TournamentLifecycleController {

    private final TournamentLifecycleService lifecycleService;

    public TournamentLifecycleController(TournamentLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    // -------------------------------------------------------------------------
    // POST /api/tournaments/{id}/mark-planned — DRAFT → PLANNED
    // -------------------------------------------------------------------------

    /**
     * Transitions the tournament from {@code DRAFT} to {@code PLANNED}.
     *
     * @param id the tournament UUID (from path)
     * @return 200 OK with the updated tournament; 404 if not found; 409 if invalid transition
     */
    @PostMapping("/{id}/mark-planned")
    public ResponseEntity<TournamentResponse> markPlanned(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(TournamentResponse.from(lifecycleService.markPlanned(id)));
    }

    // -------------------------------------------------------------------------
    // POST /api/tournaments/{id}/activate — PLANNED → ACTIVE
    // -------------------------------------------------------------------------

    /**
     * Transitions the tournament from {@code PLANNED} to {@code ACTIVE}.
     *
     * @param id the tournament UUID (from path)
     * @return 200 OK with the updated tournament; 404 if not found; 409 if invalid transition
     */
    @PostMapping("/{id}/activate")
    public ResponseEntity<TournamentResponse> activate(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(TournamentResponse.from(lifecycleService.activate(id)));
    }

    // -------------------------------------------------------------------------
    // POST /api/tournaments/{id}/complete — ACTIVE → COMPLETED
    // -------------------------------------------------------------------------

    /**
     * Transitions the tournament from {@code ACTIVE} to {@code COMPLETED}.
     *
     * @param id the tournament UUID (from path)
     * @return 200 OK with the updated tournament; 404 if not found; 409 if invalid transition
     */
    @PostMapping("/{id}/complete")
    public ResponseEntity<TournamentResponse> complete(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(TournamentResponse.from(lifecycleService.complete(id)));
    }

    // -------------------------------------------------------------------------
    // POST /api/tournaments/{id}/cancel — PLANNED or ACTIVE → CANCELLED
    // -------------------------------------------------------------------------

    /**
     * Transitions the tournament from {@code PLANNED} or {@code ACTIVE} to {@code CANCELLED}.
     *
     * <p>This endpoint only updates {@code tournament.status} — it does NOT cancel matches.
     * Match-Cancel-Lockdown belongs to E48S04 per AC-NO-MATCH-CANCEL-IN-THIS-STORY.
     *
     * @param id the tournament UUID (from path)
     * @return 200 OK with the updated tournament; 404 if not found; 409 if invalid transition
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<TournamentResponse> cancel(@PathVariable("id") UUID id) {
        return ResponseEntity.ok(TournamentResponse.from(lifecycleService.cancel(id)));
    }
}
