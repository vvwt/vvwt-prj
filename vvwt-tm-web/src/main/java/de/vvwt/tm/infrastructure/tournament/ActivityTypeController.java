package de.vvwt.tm.infrastructure.tournament;

import de.vvwt.tm.infrastructure.tournament.dto.ActivityTypeCreateRequest;
import de.vvwt.tm.infrastructure.tournament.dto.ActivityTypeResponse;
import de.vvwt.tm.infrastructure.tournament.dto.ActivityTypeUpdateRequest;
import de.vvwt.tm.tournament.activity.ActivityType;
import de.vvwt.tm.tournament.activity.ActivityTypeService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
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
 * REST controller for activity type CRUD operations (E20S02, AC2).
 *
 * <p>Reconstructed TDD-first under Approach C (E20 methodology). Behaviour reference: {@code
 * archive/E08S06-pre-dec28} commit {@code 23f2ffa}. New code was written test-first; no code was
 * copied from the archive.
 *
 * <h2>Package placement (DEC-21 / AC11)</h2>
 *
 * <p>Located at {@code de.vvwt.tm.infrastructure.tournament} — a sub-package under the existing
 * {@code infrastructure} Spring Modulith context. No new top-level module is introduced, so {@code
 * ApplicationModulesTest.verify()} passes without annotation.
 *
 * @see de.vvwt.tm.tournament.activity.ActivityTypeService
 * @see ActivityTypeResponse
 */
@RestController
@RequestMapping("/api/tournaments/{tournamentId}/activity-types")
public class ActivityTypeController {

    private final ActivityTypeService activityTypeService;

    /**
     * Constructs an {@code ActivityTypeController}.
     *
     * @param activityTypeService the domain service (NOT NULL)
     */
    public ActivityTypeController(ActivityTypeService activityTypeService) {
        this.activityTypeService = activityTypeService;
    }

    // -------------------------------------------------------------------------
    // GET /api/tournaments/{tournamentId}/activity-types
    // -------------------------------------------------------------------------

    /**
     * Lists all activity types for the given tournament.
     *
     * @param tournamentId the tournament UUID from the path
     * @return 200 with the list of activity types; 404 if the tournament is unknown
     */
    @GetMapping
    public ResponseEntity<List<ActivityTypeResponse>> list(
            @PathVariable("tournamentId") UUID tournamentId) {
        List<ActivityTypeResponse> responses =
                activityTypeService.findByTournamentId(tournamentId).stream()
                        .map(ActivityTypeResponse::from)
                        .toList();
        return ResponseEntity.ok(responses);
    }

    // -------------------------------------------------------------------------
    // POST /api/tournaments/{tournamentId}/activity-types
    // -------------------------------------------------------------------------

    /**
     * Creates a new activity type for the given tournament.
     *
     * @param tournamentId the tournament UUID from the path
     * @param request the validated request body
     * @return 201 Created with the new activity type and a Location header; 400 on validation
     *     failure; 409 on duplicate name
     */
    @PostMapping
    public ResponseEntity<ActivityTypeResponse> create(
            @PathVariable("tournamentId") UUID tournamentId,
            @Valid @RequestBody ActivityTypeCreateRequest request) {
        ActivityType created =
                activityTypeService.create(
                        tournamentId,
                        request.name(),
                        request.assignmentRule(),
                        request.capacityPerRound(),
                        request.sortOrder());
        ActivityTypeResponse response = ActivityTypeResponse.from(created);
        URI location =
                ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{id}")
                        .buildAndExpand(created.getId())
                        .toUri();
        return ResponseEntity.created(location).body(response);
    }

    // -------------------------------------------------------------------------
    // PUT /api/tournaments/{tournamentId}/activity-types/{id}
    // -------------------------------------------------------------------------

    /**
     * Updates an existing activity type.
     *
     * @param tournamentId the tournament UUID from the path
     * @param id the activity type UUID from the path
     * @param request the validated request body
     * @return 200 with the updated activity type; 400 on validation failure; 404 if not found; 409
     *     on duplicate name
     */
    @PutMapping("/{id}")
    public ResponseEntity<ActivityTypeResponse> update(
            @PathVariable("tournamentId") UUID tournamentId,
            @PathVariable("id") UUID id,
            @Valid @RequestBody ActivityTypeUpdateRequest request) {
        ActivityType updated =
                activityTypeService.update(
                        tournamentId,
                        id,
                        request.name(),
                        request.assignmentRule(),
                        request.capacityPerRound(),
                        request.sortOrder());
        return ResponseEntity.ok(ActivityTypeResponse.from(updated));
    }

    // -------------------------------------------------------------------------
    // DELETE /api/tournaments/{tournamentId}/activity-types/{id}
    // -------------------------------------------------------------------------

    /**
     * Deletes an activity type.
     *
     * @param tournamentId the tournament UUID from the path
     * @param id the activity type UUID from the path
     * @return 204 No Content on success; 404 if not found
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable("tournamentId") UUID tournamentId, @PathVariable("id") UUID id) {
        activityTypeService.delete(tournamentId, id);
        return ResponseEntity.noContent().build();
    }
}
