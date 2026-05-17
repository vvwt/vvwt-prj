// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web;

import de.vvwt.tm.phaselifecycle.DraftApplicationOrchestrator;
import de.vvwt.tm.tournament.DraftService;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.draft.DraftBreak;
import de.vvwt.tm.tournament.draft.DraftConfig;
import de.vvwt.tm.tournament.draft.DraftPreviewResult;
import de.vvwt.tm.tournament.draft.DraftSection;
import de.vvwt.tm.tournament.exceptions.TournamentNotFoundException;
import de.vvwt.tm.tournament.internal.dto.draft.DraftApplyResponse;
import de.vvwt.tm.tournament.internal.dto.draft.DraftBreakRequest;
import de.vvwt.tm.tournament.internal.dto.draft.DraftPreviewResponse;
import de.vvwt.tm.tournament.internal.dto.draft.DraftRequest;
import de.vvwt.tm.tournament.internal.dto.draft.DraftResponse;
import de.vvwt.tm.tournament.internal.dto.draft.DraftSectionRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for draft phase-planning operations (GET/PUT draft config + preview/apply).
 *
 * <h2>DEC-40 Clause A — Primary-Adapter-Isolation (E21S19)</h2>
 *
 * <p>{@code DraftController} relocated whole-class from {@code de.vvwt.tm.tournament} to {@code
 * de.vvwt.tm.web} per DEC-40 Clause A (Q-1b whole-class move per DEC-22 §refactor-clause; Q-1a
 * authorship verified at story-authoring time). New {@code GET} and {@code PUT} mappings added in
 * this Story under DEC-22 Iron Law (Q-1a fresh RED-first per AC-TEST-GET-EMPTY-RED etc.).
 *
 * <h2>Endpoints</h2>
 *
 * <ul>
 *   <li>GET /api/tournaments/{tournamentId}/draft → 200 + {@link DraftResponse} (E21S19 Scenario
 *       A/B)
 *   <li>PUT /api/tournaments/{tournamentId}/draft → 200 + {@link DraftResponse} (E21S19 Scenario
 *       C/D)
 *   <li>POST /api/tournaments/{tournamentId}/draft/preview → 200 + {@link DraftPreviewResponse}
 *       (pre-existing E21S07)
 *   <li>POST /api/tournaments/{tournamentId}/draft/apply → 200 + {@link DraftApplyResponse}
 *       (pre-existing E21S07)
 * </ul>
 *
 * <h2>Exception handling</h2>
 *
 * <p>Exception mapping is delegated to {@link GlobalExceptionHandler}:
 *
 * <ul>
 *   <li>{@link de.vvwt.tm.tournament.exceptions.TournamentNotFoundException} → 404 (via
 *       {@code @ResponseStatus(NOT_FOUND)} on the exception class)
 *   <li>{@link de.vvwt.tm.tournament.exceptions.ConflictException} (including {@link
 *       de.vvwt.tm.tournament.exceptions.TournamentNotInDraftException} for non-DRAFT apply
 *       attempts — E48S22) → 409
 *   <li>Spring MVC {@code HttpMessageNotReadableException} → 400 (via Spring's default handler)
 * </ul>
 *
 * <h2>Security</h2>
 *
 * <p>All {@code /api/**} endpoints require HTTP Basic authentication. Tenant scoping is enforced at
 * the service/repository layer via TenantContext (DEC-20). Cross-tenant access returns 404 via the
 * natural TenantContext + TournamentNotFoundException mechanic (AC-CROSS-TENANT-404).
 *
 * @see DraftService
 * @see DraftApplicationOrchestrator
 * @see DraftResponse
 * @see GlobalExceptionHandler
 * @see <a href="DEC-40">DEC-40 — Primary-Adapter-Isolation: controller relocation to web</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law: Q-1a fresh RED-first for GET/PUT</a>
 * @see <a href="DEC-20">DEC-20 — TenantContext enforcement at repository layer</a>
 * @see <a href="DEC-21">DEC-21 — Spring Modulith: web → phaselifecycle → tournament topology</a>
 * @see <a href="DEC-64">DEC-64 D-11 — Option C: DraftApplicationOrchestrator in phaselifecycle</a>
 * @see <a href="E21S07">E21S07 — Draft phase-planning reconstruction (original preview/apply)</a>
 * @see <a href="E21S19">E21S19 — Restore GET/PUT + DraftController relocation to de.vvwt.tm.web</a>
 * @see <a href="E55S06">E55S06 — Migration: apply() rewired to DraftApplicationOrchestrator</a>
 */
@RestController("tmDraftController")
@RequestMapping("/api/tournaments/{tournamentId}/draft")
public class DraftController {

    private final DraftService draftService;
    private final TournamentRepository tournamentRepository;
    private final DraftApplicationOrchestrator draftApplicationOrchestrator;

    /**
     * @param draftService the draft service ({@code "tmDraftService"} qualifier) — used for
     *     preview, loadDraft, saveDraft, resetPlan operations (unchanged E55S06)
     * @param tournamentRepository the tournament repository ({@code "tmTournamentRepository"}
     *     qualifier) — used to load {@link Tournament#getTeamCount()} for the preview computation
     *     (E21S21 AC-IMPL-BE-CONTROLLER-LOAD-TEAMCOUNT)
     * @param draftApplicationOrchestrator the apply-and-orchestrate entry-point in the {@code
     *     phaselifecycle} module (E55S06, Option C, DEC-64 D-11). Used for the {@code POST /apply}
     *     endpoint instead of {@link DraftService#apply} directly — the orchestrator additionally
     *     enqueues job rows and triggers the per-tournament worker.
     */
    public DraftController(
            @Qualifier("tmDraftService") DraftService draftService,
            @Qualifier("tmTournamentRepository") TournamentRepository tournamentRepository,
            DraftApplicationOrchestrator draftApplicationOrchestrator) {
        this.draftService = draftService;
        this.tournamentRepository = tournamentRepository;
        this.draftApplicationOrchestrator = draftApplicationOrchestrator;
    }

    // -------------------------------------------------------------------------
    // GET — load draft configuration (E21S19 Scenarios A and B)
    // -------------------------------------------------------------------------

    /**
     * Returns the current draft configuration for a tournament.
     *
     * <p>Returns {@code 200 OK} with {@link DraftResponse}{@code {sections: []}} if no draft has
     * been saved yet (Scenario B). Returns the previously saved sections if a draft was persisted
     * via PUT (Scenario A). Discovery-anchored choice: 200-with-empty-list (not 404) because the
     * frontend {@code DraftConfig.svelte:64-71} assigns {@code sections = config.sections.map(...)}
     * directly and would throw on a 404 body.
     *
     * <p>Tenant scoping via TenantContext at the repository layer; cross-tenant access → 404 via
     * TournamentNotFoundException (AC-CROSS-TENANT-404, Scenario F).
     *
     * @param tournamentId the tournament UUID (from path)
     * @return 200 OK with DraftResponse (sections may be empty); 404 if tournament not found
     */
    @GetMapping("")
    public ResponseEntity<DraftResponse> getDraft(@PathVariable("tournamentId") UUID tournamentId) {
        DraftConfig config = draftService.loadDraft(tournamentId);
        return ResponseEntity.ok(DraftResponse.from(config));
    }

    // -------------------------------------------------------------------------
    // PUT — save draft configuration (E21S19 Scenarios C, D, E, F, G)
    // -------------------------------------------------------------------------

    /**
     * Saves the draft configuration for a {@code DRAFT}-status tournament.
     *
     * <p>Returns {@code 200 OK} with {@link DraftResponse} echoing the persisted draft (Scenario
     * C). Returns {@code 409 Conflict} if the tournament is not in {@code DRAFT} status (Scenario
     * D). Returns {@code 400 Bad Request} if the body is not parseable as {@link DraftRequest}
     * (Scenario E — handled by Spring MVC via {@code HttpMessageNotReadableException}). Returns
     * {@code 404 Not Found} on cross-tenant access (Scenario F — via TournamentNotFoundException).
     * Two consecutive identical PUTs return byte-equivalent responses (Scenario G — the underlying
     * column overwrite is naturally idempotent).
     *
     * @param tournamentId the tournament UUID (from path)
     * @param request the draft configuration to save (validated via {@link Valid})
     * @return 200 OK with DraftResponse echoing the saved draft; 404/409/400 on errors
     */
    @PutMapping("")
    public ResponseEntity<DraftResponse> saveDraft(
            @PathVariable("tournamentId") UUID tournamentId,
            @RequestBody @Valid DraftRequest request) {
        DraftConfig config = toDraftConfig(request);
        DraftConfig saved = draftService.saveDraft(tournamentId, config);
        return ResponseEntity.ok(DraftResponse.from(saved));
    }

    // -------------------------------------------------------------------------
    // POST /preview — preview draft computation (pre-existing E21S07)
    // -------------------------------------------------------------------------

    /**
     * Calculates a preview of what the draft will produce WITHOUT creating any entities.
     *
     * <p>E21S21 AC-IMPL-BE-CONTROLLER-LOAD-TEAMCOUNT: loads {@link Tournament#getTeamCount()} from
     * the repository to supply the correct participating team count to the service — replacing the
     * pre-fix hardcoded {@code 0} which caused meaningless preview math (Bug 1b). Returns 404 if
     * the tournament is not found (AC-TEST-BE-CROSS-TENANT-404-PRESERVED).
     *
     * <p>E48S10 AC-IMPL-DRAFT-CONTROLLER-LOAD-FIELDCOUNT: additionally loads {@link
     * Tournament#getFieldCount()} from the SAME Tournament instance already fetched for {@code
     * getTeamCount()} (no second query) and passes it through to the service for the
     * field-count-aware lap formula.
     *
     * <p>E48S12 AC-IMPL-LOCAL-DATE-TIME-NARROWING: {@link Tournament#getPlannedStartTime()} is
     * already a {@link java.time.LocalTime} (stored as TIME column in the DB). Passed directly to
     * {@link DraftService#preview} for timeline population when non-null.
     *
     * @param tournamentId the tournament UUID (from path)
     * @param request the draft configuration to preview (validated via {@link Valid})
     * @return 200 OK with the preview result for each section; 404 if tournament not found
     */
    @PostMapping("/preview")
    public ResponseEntity<DraftPreviewResponse> previewDraft(
            @PathVariable("tournamentId") UUID tournamentId,
            @RequestBody @Valid DraftRequest request) {

        Tournament tournament =
                tournamentRepository
                        .findById(tournamentId)
                        .orElseThrow(() -> new TournamentNotFoundException(tournamentId));
        DraftConfig config = toDraftConfig(request);
        // E48S10 AC-IMPL-DRAFT-CONTROLLER-LOAD-FIELDCOUNT: pass fieldCount from same Tournament
        // instance (no extra query) for the field-count-aware lap formula.
        // E48S12 AC-IMPL-LOCAL-DATE-TIME-NARROWING: Tournament.plannedStartTime is already
        // LocalTime (stored as TIME column); pass directly to preview() for timeline calculation.
        DraftPreviewResult result =
                draftService.preview(
                        config,
                        tournament.getTeamCount(),
                        tournament.getFieldCount(),
                        tournament.getPlannedStartTime());
        return ResponseEntity.ok(DraftPreviewResponse.from(result.sections(), result.timeline()));
    }

    // -------------------------------------------------------------------------
    // POST /apply — apply draft to create Phase entities (pre-existing E21S07)
    // -------------------------------------------------------------------------

    /**
     * Atomically applies the draft configuration (E48S22): creates Phase entities, persists
     * draft_json, transitions tournament DRAFT→PLANNED via TournamentLifecycleService, and enqueues
     * {@code phase_lifecycle_job} rows for the per-tournament worker (E55S06, Option C, DEC-64
     * D-11).
     *
     * <p>Delegates to {@link DraftApplicationOrchestrator#applyDraft(UUID, DraftConfig)} instead of
     * {@link DraftService#apply} directly (E55S06: the orchestrator lives in the {@code
     * phaselifecycle} module which is accessible from {@code web} per DEC-40 + DEC-21).
     *
     * <p>Fails with 409 if the tournament is not in DRAFT status (→ {@link
     * de.vvwt.tm.tournament.exceptions.TournamentNotInDraftException} with messageKey {@code
     * draft.error.notInDraftStatus}, via GlobalExceptionHandler).
     *
     * @param tournamentId the tournament UUID (from path)
     * @param request the draft configuration to apply (validated via {@link Valid})
     * @return 200 OK with the list of created Phase IDs
     */
    @PostMapping("/apply")
    public ResponseEntity<DraftApplyResponse> applyDraft(
            @PathVariable("tournamentId") UUID tournamentId,
            @RequestBody @Valid DraftRequest request) {

        DraftConfig config = toDraftConfig(request);
        // E55S06 Option C: delegate to DraftApplicationOrchestrator (phaselifecycle module)
        // which calls DraftService.apply() + enqueueJob() + drainNext() atomically.
        // REST contract unchanged: same request shape, same DraftApplyResponse(phaseIds) response.
        List<UUID> phaseIds = draftApplicationOrchestrator.applyDraft(tournamentId, config);
        return ResponseEntity.ok(new DraftApplyResponse(phaseIds));
    }

    // -------------------------------------------------------------------------
    // POST /reset-plan — reset Phasenplan from PLANNED back to DRAFT (E48S13)
    // -------------------------------------------------------------------------

    /**
     * Resets the Phasenplan for a {@code PLANNED} tournament back to {@code DRAFT} (E48S13,
     * AC-IMPL-REST-RESET-PLAN).
     *
     * <p>Returns {@code 200 OK} with the updated {@link Tournament} body (status flipped to {@code
     * DRAFT}). Returns {@code 409 Conflict} with a typed messageKey for any disallowed status
     * (ACTIVE, CANCELLED, COMPLETED, DRAFT-idempotent). Returns {@code 404 Not Found} if the
     * tournament does not exist.
     *
     * <p>Maps to {@link DraftService#resetPlan(UUID)}.
     *
     * @param tournamentId the tournament UUID (from path)
     * @return 200 OK with the updated Tournament body; 404/409 on errors
     */
    @PostMapping("/reset-plan")
    public ResponseEntity<Tournament> resetPlan(@PathVariable("tournamentId") UUID tournamentId) {
        Tournament updated = draftService.resetPlan(tournamentId);
        return ResponseEntity.ok(updated);
    }

    // -------------------------------------------------------------------------
    // Mapping helpers
    // -------------------------------------------------------------------------

    private DraftConfig toDraftConfig(DraftRequest request) {
        List<DraftSection> sections =
                request.sections().stream().map(DraftController::toDraftSection).toList();
        return new DraftConfig(sections);
    }

    private static DraftSection toDraftSection(DraftSectionRequest req) {
        List<DraftBreak> breaks =
                req.breaks() == null
                        ? List.of()
                        : req.breaks().stream().map(DraftController::toDraftBreak).toList();
        // E51S15: pass distributionMode (optional — null defaults to "sequential" in DraftSection)
        return new DraftSection(
                req.sectionNumber(),
                req.sortType(),
                req.groupCount(),
                req.gameMode(),
                req.lapBreakTimeMinutes(),
                req.sectionBreakTimeMinutes(),
                req.lapTimeMinutes(),
                req.setQuantity(),
                breaks,
                req.distributionMode());
    }

    private static DraftBreak toDraftBreak(DraftBreakRequest req) {
        return new DraftBreak(req.afterLapNumber(), req.durationMinutes(), req.label());
    }
}
