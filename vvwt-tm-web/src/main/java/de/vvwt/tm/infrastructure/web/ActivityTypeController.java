package de.vvwt.tm.infrastructure.web;

import de.vvwt.tm.domain.ActivityType;
import de.vvwt.tm.domain.ActivityTypeService;
import de.vvwt.tm.infrastructure.web.dto.ActivityTypeCreateRequest;
import de.vvwt.tm.infrastructure.web.dto.ActivityTypeResponse;
import de.vvwt.tm.infrastructure.web.dto.ActivityTypeUpdateRequest;
import jakarta.validation.Valid;
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

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * REST controller for ActivityType CRUD operations (E08S06, AC1, AC7, AC9).
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>GET    /api/tournaments/{tournamentId}/activity-types      — list all (AC1)</li>
 *   <li>POST   /api/tournaments/{tournamentId}/activity-types      — create new (AC1)</li>
 *   <li>PUT    /api/tournaments/{tournamentId}/activity-types/{id} — update (AC1)</li>
 *   <li>DELETE /api/tournaments/{tournamentId}/activity-types/{id} — delete (AC1)</li>
 * </ul>
 *
 * <h2>Error handling</h2>
 * <p>Exceptions are mapped by {@link GlobalExceptionHandler}:
 * <ul>
 *   <li>{@link java.util.NoSuchElementException} → 404</li>
 *   <li>{@link ConflictException} → 409 (duplicate name — AC7)</li>
 *   <li>{@link IllegalArgumentException} → 400 (invalid rule, invalid capacity)</li>
 *   <li>{@link org.springframework.web.bind.MethodArgumentNotValidException} → 400</li>
 * </ul>
 *
 * <h2>Security (AC9)</h2>
 * <p>All /api/** endpoints require HTTP Basic authentication (SecurityConfig).
 * Tenant scoping is enforced at the repository layer (DEC-5, DEC-17).
 *
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E08S06.story.md">Story E08S06</a>
 */
@RestController
@RequestMapping("/api/tournaments/{tournamentId}/activity-types")
public class ActivityTypeController {

    private final ActivityTypeService activityTypeService;

    public ActivityTypeController(ActivityTypeService activityTypeService) {
        this.activityTypeService = activityTypeService;
    }

    // -------------------------------------------------------------------------
    // AC1 — GET /api/tournaments/{tournamentId}/activity-types
    // -------------------------------------------------------------------------

    /**
     * Returns all activity types for the given tournament, ordered by {@code sort_order}.
     *
     * @param tournamentId the tournament UUID (from path)
     * @return 200 OK with JSON array; empty array if no activity types configured
     */
    @GetMapping
    public ResponseEntity<List<ActivityTypeResponse>> listActivityTypes(
            @PathVariable("tournamentId") UUID tournamentId) {
        List<ActivityTypeResponse> responses = activityTypeService.findByTournamentId(tournamentId)
                .stream()
                .map(ActivityTypeResponse::from)
                .toList();
        return ResponseEntity.ok(responses);
    }

    // -------------------------------------------------------------------------
    // AC1 — POST /api/tournaments/{tournamentId}/activity-types
    // -------------------------------------------------------------------------

    /**
     * Creates a new activity type for the given tournament.
     *
     * @param tournamentId the tournament UUID (from path)
     * @param request      the creation request body (validated)
     * @return 201 Created with the new activity type and a {@code Location} header
     */
    @PostMapping
    public ResponseEntity<ActivityTypeResponse> createActivityType(
            @PathVariable("tournamentId") UUID tournamentId,
            @RequestBody @Valid ActivityTypeCreateRequest request) {

        ActivityType created = activityTypeService.create(
                tournamentId,
                request.name(),
                request.assignmentRule(),
                request.capacityPerRound(),
                request.sortOrder() != null ? request.sortOrder() : 0);

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.getId())
                .toUri();

        return ResponseEntity.created(location).body(ActivityTypeResponse.from(created));
    }

    // -------------------------------------------------------------------------
    // AC1 — PUT /api/tournaments/{tournamentId}/activity-types/{id}
    // -------------------------------------------------------------------------

    /**
     * Updates an existing activity type.
     *
     * @param tournamentId the tournament UUID (from path)
     * @param id           the activity type UUID (from path)
     * @param request      the update request body (validated)
     * @return 200 OK with the updated activity type; 404 if not found; 409 if duplicate name
     */
    @PutMapping("/{id}")
    public ResponseEntity<ActivityTypeResponse> updateActivityType(
            @PathVariable("tournamentId") UUID tournamentId,
            @PathVariable("id") UUID id,
            @RequestBody @Valid ActivityTypeUpdateRequest request) {

        ActivityType updated = activityTypeService.update(
                tournamentId,
                id,
                request.name(),
                request.assignmentRule(),
                request.capacityPerRound(),
                request.sortOrder() != null ? request.sortOrder() : 0);

        return ResponseEntity.ok(ActivityTypeResponse.from(updated));
    }

    // -------------------------------------------------------------------------
    // AC1 — DELETE /api/tournaments/{tournamentId}/activity-types/{id}
    // -------------------------------------------------------------------------

    /**
     * Deletes an activity type immediately (no dependent data — assignments are computed).
     *
     * @param tournamentId the tournament UUID (from path)
     * @param id           the activity type UUID (from path)
     * @return 204 No Content on success; 404 if not found
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteActivityType(
            @PathVariable("tournamentId") UUID tournamentId,
            @PathVariable("id") UUID id) {
        activityTypeService.delete(tournamentId, id);
        return ResponseEntity.noContent().build();
    }
}
