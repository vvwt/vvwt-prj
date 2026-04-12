package de.vvwt.tm.infrastructure.web;

import de.vvwt.tm.domain.RefereeAssignmentService;
import de.vvwt.tm.domain.RefereeAssignmentService.RefereeAssignmentEntry;
import de.vvwt.tm.domain.RefereeAssignmentService.RefereeAssignmentOverview;
import de.vvwt.tm.infrastructure.web.dto.RefereeAssignmentEntryResponse;
import de.vvwt.tm.infrastructure.web.dto.RefereeAssignmentOverviewResponse;
import de.vvwt.tm.infrastructure.web.dto.RefereeOverrideRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller for referee assignment operations on a phase (E05S09).
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>GET  /api/phases/{phaseId}/referee-assignments              — overview (AC1, AC7, AC9)</li>
 *   <li>PUT  /api/phases/{phaseId}/matches/{matchId}/referee        — set manual override (AC2)</li>
 *   <li>DELETE /api/phases/{phaseId}/matches/{matchId}/referee      — clear override (AC3)</li>
 *   <li>POST /api/phases/{phaseId}/referee-assignments/reassign     — re-run auto-assignment (AC4)</li>
 * </ul>
 *
 * <h2>Error handling</h2>
 * <p>All exceptions are mapped to structured JSON responses by {@link GlobalExceptionHandler}:
 * <ul>
 *   <li>{@link java.util.NoSuchElementException} → 404 (phase/match/avatar not found)</li>
 *   <li>{@link ConflictException} → 409 (playing constraint violated — AC2)</li>
 *   <li>{@link org.springframework.web.bind.MethodArgumentNotValidException} → 400</li>
 * </ul>
 *
 * <h2>Security (AC11)</h2>
 * <p>All /api/** endpoints require HTTP Basic authentication per
 * {@link de.vvwt.tm.auth.SecurityConfig}. Tenant scoping is enforced at the
 * {@link RefereeAssignmentService} level via the tenant-scoped repositories (DEC-5, DEC-17).
 *
 * @see RefereeAssignmentService
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E05S09.story.md">Story E05S09</a>
 */
@RestController
public class RefereeAssignmentController {

    private final RefereeAssignmentService refereeAssignmentService;

    public RefereeAssignmentController(RefereeAssignmentService refereeAssignmentService) {
        this.refereeAssignmentService = refereeAssignmentService;
    }

    // -------------------------------------------------------------------------
    // AC1 — GET /api/phases/{phaseId}/referee-assignments
    // -------------------------------------------------------------------------

    /**
     * Returns the full referee assignment overview for the phase (AC1, AC7, AC9).
     *
     * <p>Response includes:
     * <ul>
     *   <li>{@code assignments} — all matches with current referee and isManualOverride flag</li>
     *   <li>{@code teamSummary} — per-team assignment count (AC7)</li>
     *   <li>{@code allRefereeTeams} — eligible referee teams for the override dropdown (AC6)</li>
     * </ul>
     *
     * <p>If no teams have {@code refereeAssignment=true}, the {@code allRefereeTeams} list is
     * empty and all assignment entries have {@code refereeTeamName=null} (AC9).
     *
     * @param phaseId the phase to query
     * @return 200 OK with the overview, or 404 if phase not found or cross-tenant
     */
    @GetMapping("/api/phases/{phaseId}/referee-assignments")
    public ResponseEntity<RefereeAssignmentOverviewResponse> getAssignments(
            @PathVariable("phaseId") UUID phaseId) {

        RefereeAssignmentOverview overview = refereeAssignmentService.getAssignments(phaseId);
        return ResponseEntity.ok(RefereeAssignmentOverviewResponse.from(overview));
    }

    // -------------------------------------------------------------------------
    // AC2 — PUT /api/phases/{phaseId}/matches/{matchId}/referee
    // -------------------------------------------------------------------------

    /**
     * Sets a manual referee override for the given match (AC2).
     *
     * <p>Validates the hard constraint: the assigned team cannot be playing in the same lap.
     * Returns 409 if the constraint is violated, with a message identifying the conflict.
     *
     * @param phaseId  the phase containing the match
     * @param matchId  the match to override
     * @param request  the override request body ({@code refereeTeamAvatarId} — must not be null)
     * @return 200 OK with the updated assignment entry, 404 if not found, 409 on constraint violation
     */
    @PutMapping("/api/phases/{phaseId}/matches/{matchId}/referee")
    public ResponseEntity<RefereeAssignmentEntryResponse> overrideReferee(
            @PathVariable("phaseId") UUID phaseId,
            @PathVariable("matchId") UUID matchId,
            @RequestBody @Valid RefereeOverrideRequest request) {

        RefereeAssignmentEntry entry = refereeAssignmentService.overrideReferee(
                phaseId, matchId, request.refereeTeamAvatarId());
        return ResponseEntity.ok(RefereeAssignmentEntryResponse.from(entry));
    }

    // -------------------------------------------------------------------------
    // AC3 — DELETE /api/phases/{phaseId}/matches/{matchId}/referee
    // -------------------------------------------------------------------------

    /**
     * Clears a manual referee override for the given match (AC3).
     *
     * <p>Sets both {@code refereeTeamId} and {@code refereeDescription} to null.
     * The organizer can use {@code POST /reassign} (AC4) to re-run auto-assignment.
     *
     * @param phaseId the phase containing the match
     * @param matchId the match whose override to clear
     * @return 204 No Content on success, 404 if not found
     */
    @DeleteMapping("/api/phases/{phaseId}/matches/{matchId}/referee")
    public ResponseEntity<Void> clearRefereeOverride(
            @PathVariable("phaseId") UUID phaseId,
            @PathVariable("matchId") UUID matchId) {

        refereeAssignmentService.clearRefereeOverride(phaseId, matchId);
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // AC4 — POST /api/phases/{phaseId}/referee-assignments/reassign
    // -------------------------------------------------------------------------

    /**
     * Re-runs the auto-assignment algorithm for all non-manually-overridden matches (AC4).
     *
     * <p>Manual overrides (matches where the organizer explicitly set a referee via AC2)
     * are preserved. Only matches without a manual override are re-assigned.
     *
     * @param phaseId the phase to re-assign
     * @return 200 OK with the updated full overview, 404 if phase not found
     */
    @PostMapping("/api/phases/{phaseId}/referee-assignments/reassign")
    public ResponseEntity<RefereeAssignmentOverviewResponse> reassignAll(
            @PathVariable("phaseId") UUID phaseId) {

        RefereeAssignmentOverview overview = refereeAssignmentService.reassignAll(phaseId);
        return ResponseEntity.ok(RefereeAssignmentOverviewResponse.from(overview));
    }
}
