// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web;

import de.vvwt.tm.tournament.TournamentService;
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
 * REST controller for Tournament CRUD operations — primary-adapter-isolation target (DEC-40 Clause
 * A).
 *
 * <p>Relocated whole-class from {@code de.vvwt.tm.tournament.TournamentController} to {@code
 * de.vvwt.tm.web} per DEC-40 Clause D (Q-1b whole-class relocation, DEC-22 §refactor-clause). URL
 * mappings and JSON wire format preserved byte-equivalent (C-14). {@code @Qualifier} preserved
 * verbatim (C-12).
 *
 * <h2>Endpoints</h2>
 *
 * <ul>
 *   <li>GET /api/tournaments — list all (AC1)
 *   <li>GET /api/tournaments/{id} — get one (AC2)
 *   <li>POST /api/tournaments — create new in DRAFT (AC3)
 *   <li>PUT /api/tournaments/{id} — update DRAFT only (AC4)
 *   <li>DELETE /api/tournaments/{id} — delete DRAFT with no phases (AC5)
 * </ul>
 *
 * @see TournamentService
 * @see TournamentResponse
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation</a>
 * @see <a href="DEC-22">DEC-22 — TDD reconstruction-in-place</a>
 * @see <a href="E22S07">E22S07 — Relocate TournamentController to de.vvwt.tm.web</a>
 */
@RestController("tmTournamentController")
@RequestMapping("/api/tournaments")
public class TournamentController {

    private final TournamentService tournamentService;

    public TournamentController(
            @Qualifier("tmTournamentService") TournamentService tournamentService) {
        this.tournamentService = tournamentService;
    }

    // -------------------------------------------------------------------------
    // AC1 — GET /api/tournaments
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
    // AC2 — GET /api/tournaments/{id}
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
    // AC3 — POST /api/tournaments
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
                        request.matchGeneratorId(),
                        // E48S14 bug-fix: pass plannedStartTime to the service (was previously
                        // omitted, causing the field to be silently dropped on CREATE).
                        request.plannedStartTime(),
                        // E51S07 AC-IMPL-TOURNAMENT-FORM-CHECKBOX: pass optimize flag.
                        request.optimize(),
                        // E53S05 AC1: pass seedMannschaftsfoto flag; null → server default true.
                        request.seedMannschaftsfoto(),
                        // E68S01: pass organizer; null → service/repository falls back to tenant
                        // display_name snapshot (E46S01 backward compat).
                        request.organizer());

        URI location =
                ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{id}")
                        .buildAndExpand(tournament.getId())
                        .toUri();

        return ResponseEntity.created(location).body(TournamentResponse.from(tournament));
    }

    // -------------------------------------------------------------------------
    // AC4 — PUT /api/tournaments/{id}
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
                        request.plannedStartTime(),
                        // E51S07 AC-IMPL-TOURNAMENT-FORM-CHECKBOX: pass optimize flag.
                        request.optimize(),
                        // E68S01: pass organizer; null = no change (nullable-field convention).
                        request.organizer());

        return ResponseEntity.ok(TournamentResponse.from(tournament));
    }

    // -------------------------------------------------------------------------
    // AC5 — DELETE /api/tournaments/{id}
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
