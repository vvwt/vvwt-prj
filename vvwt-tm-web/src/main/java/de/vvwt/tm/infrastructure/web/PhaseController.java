package de.vvwt.tm.infrastructure.web;

import de.vvwt.tm.domain.MappingAssignment;
import de.vvwt.tm.domain.MappingSuggestion;
import de.vvwt.tm.domain.Phase;
import de.vvwt.tm.domain.PhaseLifecycleService;
import de.vvwt.tm.domain.PhaseMappingService;
import de.vvwt.tm.domain.PhasePreparationResult;
import de.vvwt.tm.domain.TeamAvatar;
import de.vvwt.tm.infrastructure.web.dto.AdvanceLapRequest;
import de.vvwt.tm.infrastructure.web.dto.MappingApplyRequest;
import de.vvwt.tm.infrastructure.web.dto.MappingApplyResponse;
import de.vvwt.tm.infrastructure.web.dto.MappingSuggestionResponse;
import de.vvwt.tm.infrastructure.web.dto.PhasePreparationResponse;
import de.vvwt.tm.infrastructure.web.dto.PhaseResponse;
import de.vvwt.tm.infrastructure.web.dto.PhaseScheduleResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST controller for phase lifecycle and mapping operations (E05S07, E05S08).
 *
 * <h2>Endpoints (E05S07)</h2>
 * <ul>
 *   <li>POST /api/phases/{phaseId}/prepare         — trigger preparation steps 1–3 (AC1)</li>
 *   <li>GET  /api/phases/{phaseId}                 — get phase with status and match counts (AC2)</li>
 *   <li>GET  /api/tournaments/{id}/phases          — list all phases for a tournament (AC3)</li>
 *   <li>POST /api/phases/{phaseId}/start           — trigger step 4 / start phase (AC4)</li>
 *   <li>POST /api/phases/{phaseId}/advance-lap     — manual lap advance with force override (AC5)</li>
 *   <li>GET  /api/phases/{phaseId}/schedule        — generated match schedule (AC6)</li>
 * </ul>
 *
 * <h2>Endpoints (E05S08 — team mapping)</h2>
 * <ul>
 *   <li>GET  /api/phases/{phaseId}/mapping-suggestion — generate mapping suggestion (AC1)</li>
 *   <li>POST /api/phases/{phaseId}/mapping             — apply mapping / create TeamAvatars (AC2)</li>
 *   <li>POST /api/phases/{phaseId}/mapping/redo        — re-do mapping: delete + re-apply (AC10)</li>
 * </ul>
 *
 * <h2>Error handling</h2>
 * <p>All exceptions are mapped to structured JSON responses by {@link GlobalExceptionHandler}:
 * <ul>
 *   <li>{@link java.util.NoSuchElementException}                         → 404</li>
 *   <li>{@link ConflictException}                                        → 409</li>
 *   <li>{@link org.springframework.dao.DataIntegrityViolationException}  → 409 (DEC-5 guard)</li>
 *   <li>{@link Exception}                                                → 500</li>
 * </ul>
 *
 * <h2>Security (AC15)</h2>
 * <p>All /api/** endpoints require HTTP Basic authentication per
 * {@link de.vvwt.tm.auth.SecurityConfig}. Tenant scoping is enforced at the repository
 * layer (DEC-5, DEC-17). The {@link PhaseLifecycleService} additionally performs an explicit
 * tenant ownership check on each phase.
 *
 * @see PhaseLifecycleService
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S07.story.md">Story E05S07</a>
 */
@RestController
public class PhaseController {

    private final PhaseLifecycleService phaseLifecycleService;
    private final PhaseMappingService phaseMappingService;

    public PhaseController(PhaseLifecycleService phaseLifecycleService,
                           PhaseMappingService phaseMappingService) {
        this.phaseLifecycleService = phaseLifecycleService;
        this.phaseMappingService = phaseMappingService;
    }

    // -------------------------------------------------------------------------
    // AC1 — POST /api/phases/{phaseId}/prepare
    // -------------------------------------------------------------------------

    /**
     * Triggers phase preparation steps 1–3 (generateMatches, optimizeSlots, assignReferees).
     *
     * <p>Returns a per-step result. If any step fails, subsequent steps are skipped.
     * The overall {@code success} flag is {@code false} if any step failed (AC12).
     *
     * @param phaseId the phase to prepare
     * @return 200 OK with preparation result
     */
    @PostMapping("/api/phases/{phaseId}/prepare")
    public ResponseEntity<PhasePreparationResponse> preparePhase(
            @PathVariable("phaseId") UUID phaseId) {

        PhasePreparationResult result = phaseLifecycleService.prepare(phaseId);
        return ResponseEntity.ok(PhasePreparationResponse.from(result));
    }

    // -------------------------------------------------------------------------
    // AC2 — GET /api/phases/{phaseId}
    // -------------------------------------------------------------------------

    /**
     * Returns a phase with its current status, lap number, and match counts (AC2).
     *
     * @param phaseId the phase ID
     * @return 200 OK with phase detail, or 404 if not found or belongs to a different tenant
     */
    @GetMapping("/api/phases/{phaseId}")
    public ResponseEntity<PhaseResponse> getPhase(
            @PathVariable("phaseId") UUID phaseId) {

        Phase phase = phaseLifecycleService.getPhase(phaseId);
        int totalLapCount = phaseLifecycleService.getTotalLapCount(phaseId);
        var matchCounts = phaseLifecycleService.buildMatchCounts(phaseId);
        return ResponseEntity.ok(PhaseResponse.from(phase, totalLapCount, matchCounts));
    }

    // -------------------------------------------------------------------------
    // AC3 — GET /api/tournaments/{tournamentId}/phases
    // -------------------------------------------------------------------------

    /**
     * Returns all phases for the given tournament, ordered by sequence number (AC3).
     *
     * @param tournamentId the tournament ID
     * @return 200 OK with list of phases, or 404 if tournament not found
     */
    @GetMapping("/api/tournaments/{tournamentId}/phases")
    public ResponseEntity<List<PhaseResponse>> listPhases(
            @PathVariable("tournamentId") UUID tournamentId) {

        List<PhaseResponse> responses = phaseLifecycleService.listPhases(tournamentId).stream()
                .map(phase -> {
                    int totalLapCount = phaseLifecycleService.getTotalLapCount(phase.getId());
                    var matchCounts = phaseLifecycleService.buildMatchCounts(phase.getId());
                    return PhaseResponse.from(phase, totalLapCount, matchCounts);
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(responses);
    }

    // -------------------------------------------------------------------------
    // AC4 — POST /api/phases/{phaseId}/start
    // -------------------------------------------------------------------------

    /**
     * Starts the phase (PENDING → ACTIVE) and transitions the tournament to ACTIVE (AC4).
     *
     * <p>Returns 409 if preconditions are not met (missing slots, referees, or DEC-5 guard).
     *
     * @param phaseId the phase to start
     * @return 200 OK with updated phase, or 409 on conflict
     */
    @PostMapping("/api/phases/{phaseId}/start")
    public ResponseEntity<PhaseResponse> startPhase(
            @PathVariable("phaseId") UUID phaseId) {

        Phase phase = phaseLifecycleService.start(phaseId);
        int totalLapCount = phaseLifecycleService.getTotalLapCount(phaseId);
        var matchCounts = phaseLifecycleService.buildMatchCounts(phaseId);
        return ResponseEntity.ok(PhaseResponse.from(phase, totalLapCount, matchCounts));
    }

    // -------------------------------------------------------------------------
    // AC5 — POST /api/phases/{phaseId}/advance-lap
    // -------------------------------------------------------------------------

    /**
     * Manually advances the current lap number for the given ACTIVE phase (AC5).
     *
     * <p>If the current lap has unfinished matches and {@code force} is {@code false},
     * returns 409 with the count. If {@code force} is {@code true}, records an audit
     * entry and advances the lap.
     *
     * @param phaseId    the phase whose lap to advance
     * @param request    the request body; {@code force} defaults to {@code false}
     * @param auth       Spring Security authentication (for actorId; may be null in LAN mode)
     * @return 200 OK with updated phase, or 409 on conflict
     */
    @PostMapping("/api/phases/{phaseId}/advance-lap")
    public ResponseEntity<PhaseResponse> advanceLap(
            @PathVariable("phaseId") UUID phaseId,
            @RequestBody(required = false) AdvanceLapRequest request,
            Authentication auth) {

        boolean force = request != null && request.force();
        String actorId = auth != null ? auth.getName() : null;

        Phase phase = phaseLifecycleService.advanceLap(phaseId, force, actorId);
        int totalLapCount = phaseLifecycleService.getTotalLapCount(phaseId);
        var matchCounts = phaseLifecycleService.buildMatchCounts(phaseId);
        return ResponseEntity.ok(PhaseResponse.from(phase, totalLapCount, matchCounts));
    }

    // -------------------------------------------------------------------------
    // AC6 — GET /api/phases/{phaseId}/schedule
    // -------------------------------------------------------------------------

    /**
     * Returns the generated match schedule for the phase, organized by lap and field (AC6).
     *
     * @param phaseId the phase whose schedule to retrieve
     * @return 200 OK with schedule, or 404 if phase not found
     */
    @GetMapping("/api/phases/{phaseId}/schedule")
    public ResponseEntity<PhaseScheduleResponse> getSchedule(
            @PathVariable("phaseId") UUID phaseId) {

        var laps = phaseLifecycleService.getSchedule(phaseId);
        return ResponseEntity.ok(PhaseScheduleResponse.from(phaseId, laps));
    }

    // -------------------------------------------------------------------------
    // E05S08 AC1 — GET /api/phases/{phaseId}/mapping-suggestion
    // -------------------------------------------------------------------------

    /**
     * Generates a mapping suggestion for the given PENDING Phase 2+ (E05S08 AC1).
     *
     * <p>Always returns a fresh suggestion based on the previous phase's standings —
     * ignores existing TeamAvatars for the target phase (AC1 re-call behavior).
     *
     * @param phaseId the PENDING phase to generate a suggestion for
     * @return 200 OK with suggestion, or 404 if phase not found, or 409 if previous phase not COMPLETED
     */
    @GetMapping("/api/phases/{phaseId}/mapping-suggestion")
    public ResponseEntity<MappingSuggestionResponse> getMappingSuggestion(
            @PathVariable("phaseId") UUID phaseId) {

        MappingSuggestion suggestion = phaseMappingService.getMappingSuggestion(phaseId);
        return ResponseEntity.ok(MappingSuggestionResponse.from(suggestion));
    }

    // -------------------------------------------------------------------------
    // E05S08 AC2 — POST /api/phases/{phaseId}/mapping
    // -------------------------------------------------------------------------

    /**
     * Applies a mapping by creating TeamAvatar entities for the target phase (E05S08 AC2).
     *
     * <p>Returns 409 if TeamAvatars already exist (use re-do endpoint instead).
     *
     * @param phaseId the PENDING phase to assign teams to
     * @param request the mapping assignments (teamId, groupNumber, groupPosition tuples)
     * @return 201 Created with the list of created TeamAvatars
     */
    @PostMapping("/api/phases/{phaseId}/mapping")
    public ResponseEntity<MappingApplyResponse> applyMapping(
            @PathVariable("phaseId") UUID phaseId,
            @RequestBody @Valid MappingApplyRequest request) {

        List<TeamAvatar> created = phaseMappingService.applyMapping(phaseId, request.toDomain());
        return ResponseEntity.status(201).body(MappingApplyResponse.from(phaseId, created));
    }

    // -------------------------------------------------------------------------
    // E05S08 AC10 — POST /api/phases/{phaseId}/mapping/redo
    // -------------------------------------------------------------------------

    /**
     * Re-does a mapping: deletes existing TeamAvatars (and dependent data) then re-applies
     * a new mapping (E05S08 AC10).
     *
     * <p>The phase must be PENDING — cannot re-map a started phase.
     * Requires confirmation in the UI before calling (AC10 dialog).
     *
     * @param phaseId the PENDING phase to re-map
     * @param request the new mapping assignments
     * @return 201 Created with the new TeamAvatars
     */
    @PostMapping("/api/phases/{phaseId}/mapping/redo")
    public ResponseEntity<MappingApplyResponse> redoMapping(
            @PathVariable("phaseId") UUID phaseId,
            @RequestBody @Valid MappingApplyRequest request) {

        List<TeamAvatar> created = phaseMappingService.redoMapping(phaseId, request.toDomain());
        return ResponseEntity.status(201).body(MappingApplyResponse.from(phaseId, created));
    }
}
