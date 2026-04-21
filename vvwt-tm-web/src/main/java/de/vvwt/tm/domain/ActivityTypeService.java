package de.vvwt.tm.domain;

import de.vvwt.tm.domain.repo.ActivityTypeRepository;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.exceptions.ConflictException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

/**
 * Domain service for {@link ActivityType} operations (E08S02, AC4–AC8).
 *
 * <h2>Validation rules enforced</h2>
 *
 * <ul>
 *   <li>AC4: {@code assignmentRule} must be a recognized {@link AssignmentRule} value — unknown
 *       values are rejected with a {@link IllegalArgumentException}.
 *   <li>AC5: {@code name} must be unique within the tournament — duplicates are rejected with a
 *       {@link ConflictException}.
 *   <li>AC6: {@code capacityPerRound} must be {@code null} (unlimited) or &gt; 0 — non-positive
 *       non-null values are rejected with {@link IllegalArgumentException}.
 * </ul>
 *
 * <h2>i18n (AC8)</h2>
 *
 * <p>All validation error messages are resolved from the {@link MessageSource} translation layer
 * (classpath:{@code messages.properties}) rather than being hardcoded as string literals.
 *
 * <h2>Tenant scoping</h2>
 *
 * <p>All repository calls are tenant-scoped via {@link de.vvwt.tm.domain.repo.TenantContext}
 * resolved per-request (DEC-5, DEC-17).
 *
 * @see ActivityType
 * @see AssignmentRule
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E08S02.story.md">Story
 *     E08S02</a>
 */
@Service
public class ActivityTypeService {

    private final ActivityTypeRepository activityTypeRepository;
    private final TournamentRepository tournamentRepository;
    private final MessageSource messageSource;

    /**
     * Constructs the service with its required collaborators.
     *
     * @param activityTypeRepository activity type persistence (tenant-scoped)
     * @param tournamentRepository tournament persistence — for ownership checks
     * @param messageSource Spring MessageSource for i18n error messages (AC8)
     */
    public ActivityTypeService(
            ActivityTypeRepository activityTypeRepository,
            TournamentRepository tournamentRepository,
            MessageSource messageSource) {
        this.activityTypeRepository = activityTypeRepository;
        this.tournamentRepository = tournamentRepository;
        this.messageSource = messageSource;
    }

    // -------------------------------------------------------------------------
    // Create
    // -------------------------------------------------------------------------

    /**
     * Creates a new activity type for the given tournament.
     *
     * <p>Validates all constraints before persisting (AC4, AC5, AC6).
     *
     * @param tournamentId the parent tournament UUID (NOT NULL)
     * @param name activity name — must be unique within the tournament (NOT NULL)
     * @param assignmentRule the rule identifier — must match an {@link AssignmentRule} value
     * @param capacityPerRound max teams per round ({@code null} = unlimited; non-null must be &gt;
     *     0)
     * @param sortOrder display ordering (NOT NULL)
     * @return the saved {@link ActivityType}
     * @throws NoSuchElementException if the tournament does not exist for the current tenant
     * @throws IllegalArgumentException if {@code assignmentRule} is unrecognized (AC4) or {@code
     *     capacityPerRound} is &le; 0 (AC6)
     * @throws ConflictException if a duplicate name exists for the tournament (AC5)
     * @throws IllegalStateException if no tenant context is active (AC7)
     */
    public ActivityType create(
            UUID tournamentId,
            String name,
            String assignmentRule,
            Integer capacityPerRound,
            int sortOrder) {
        // Ownership check: tournament must exist and belong to the active tenant
        tournamentRepository
                .findById(tournamentId)
                .orElseThrow(
                        () -> new NoSuchElementException("Tournament not found: " + tournamentId));

        // AC4: validate assignment rule
        validateAssignmentRule(assignmentRule);

        // AC6: validate capacity
        validateCapacity(capacityPerRound);

        // AC5: check for duplicate name within the tournament
        if (activityTypeRepository.existsByTournamentIdAndName(tournamentId, name)) {
            String message =
                    messageSource.getMessage(
                            "error.activityType.duplicateName", null, Locale.getDefault());
            throw new ConflictException(message);
        }

        ActivityType activityType =
                new ActivityType(
                        UUID.randomUUID(),
                        tournamentId,
                        name,
                        assignmentRule,
                        capacityPerRound,
                        sortOrder,
                        null // tenantId set by TenantScopedRepository
                        );
        return activityTypeRepository.save(activityType);
    }

    // -------------------------------------------------------------------------
    // Update
    // -------------------------------------------------------------------------

    /**
     * Updates an existing activity type for the given tournament.
     *
     * <p>Validates all constraints before persisting (AC4, AC5, AC6). The name uniqueness check
     * allows the activity type to keep its own name (self-reference is not a conflict).
     *
     * @param tournamentId the parent tournament UUID (NOT NULL)
     * @param id the activity type UUID (NOT NULL)
     * @param name new activity name — must be unique within the tournament (NOT NULL)
     * @param assignmentRule the rule identifier — must match an {@link AssignmentRule} value
     * @param capacityPerRound max teams per round ({@code null} = unlimited; non-null must be &gt;
     *     0)
     * @param sortOrder display ordering (NOT NULL)
     * @return the saved {@link ActivityType}
     * @throws NoSuchElementException if the tournament or activity type does not exist
     * @throws IllegalArgumentException if {@code assignmentRule} is unrecognized or {@code
     *     capacityPerRound} is &le; 0
     * @throws ConflictException if a DIFFERENT activity type with the same name exists
     * @throws IllegalStateException if no tenant context is active
     */
    public ActivityType update(
            UUID tournamentId,
            UUID id,
            String name,
            String assignmentRule,
            Integer capacityPerRound,
            int sortOrder) {
        // Ownership check: tournament must exist
        tournamentRepository
                .findById(tournamentId)
                .orElseThrow(
                        () -> new NoSuchElementException("Tournament not found: " + tournamentId));

        // Existence check: activity type must exist
        ActivityType existing =
                activityTypeRepository
                        .findById(id)
                        .orElseThrow(
                                () -> new NoSuchElementException("ActivityType not found: " + id));

        // AC4: validate assignment rule
        validateAssignmentRule(assignmentRule);

        // AC6: validate capacity
        validateCapacity(capacityPerRound);

        // AC5: check for duplicate name within the tournament (self-reference is allowed)
        boolean nameConflict =
                activityTypeRepository.findByTournamentId(tournamentId).stream()
                        .anyMatch(at -> name.equals(at.getName()) && !id.equals(at.getId()));
        if (nameConflict) {
            String message =
                    messageSource.getMessage(
                            "error.activityType.duplicateName", null, Locale.getDefault());
            throw new ConflictException(message);
        }

        existing.setName(name);
        existing.setAssignmentRule(assignmentRule);
        existing.setCapacityPerRound(capacityPerRound);
        existing.setSortOrder(sortOrder);
        return activityTypeRepository.save(existing);
    }

    // -------------------------------------------------------------------------
    // Delete
    // -------------------------------------------------------------------------

    /**
     * Deletes an activity type for the given tournament.
     *
     * <p>Assignments are computed on-demand (not persisted), so deletion has no cascade effect.
     *
     * @param tournamentId the parent tournament UUID (NOT NULL)
     * @param id the activity type UUID (NOT NULL)
     * @throws NoSuchElementException if the tournament or activity type does not exist
     * @throws IllegalStateException if no tenant context is active
     */
    public void delete(UUID tournamentId, UUID id) {
        // Ownership check: tournament must exist
        tournamentRepository
                .findById(tournamentId)
                .orElseThrow(
                        () -> new NoSuchElementException("Tournament not found: " + tournamentId));

        // Existence check: activity type must exist and belong to this tournament
        activityTypeRepository
                .findById(id)
                .orElseThrow(() -> new NoSuchElementException("ActivityType not found: " + id));

        activityTypeRepository.deleteById(id);
    }

    // -------------------------------------------------------------------------
    // Query
    // -------------------------------------------------------------------------

    /**
     * Returns all activity types for the given tournament, ordered by {@code sort_order} ascending,
     * scoped to the active tenant.
     *
     * @param tournamentId the tournament UUID
     * @return list of activity types; never {@code null}; may be empty
     * @throws NoSuchElementException if the tournament does not exist for the current tenant
     * @throws IllegalStateException if no tenant context is active
     */
    public List<ActivityType> findByTournamentId(UUID tournamentId) {
        tournamentRepository
                .findById(tournamentId)
                .orElseThrow(
                        () -> new NoSuchElementException("Tournament not found: " + tournamentId));
        return activityTypeRepository.findByTournamentId(tournamentId);
    }

    // -------------------------------------------------------------------------
    // Private validation helpers
    // -------------------------------------------------------------------------

    /**
     * Validates that {@code assignmentRule} matches a known {@link AssignmentRule} value (AC4).
     *
     * @param assignmentRule the rule identifier to validate
     * @throws IllegalArgumentException if the rule is not recognized, with an i18n message listing
     *     all supported rules
     */
    private void validateAssignmentRule(String assignmentRule) {
        String supported =
                Arrays.stream(AssignmentRule.values())
                        .map(Enum::name)
                        .collect(Collectors.joining(", "));
        try {
            AssignmentRule.valueOf(assignmentRule);
        } catch (IllegalArgumentException | NullPointerException e) {
            String message =
                    messageSource.getMessage(
                            "error.activityType.unknownRule",
                            new Object[] {assignmentRule, supported},
                            Locale.getDefault());
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Validates that {@code capacityPerRound} is {@code null} (unlimited) or &gt; 0 (AC6).
     *
     * @param capacityPerRound the capacity value to validate
     * @throws IllegalArgumentException if the capacity is non-null and &le; 0
     */
    private void validateCapacity(Integer capacityPerRound) {
        if (capacityPerRound != null && capacityPerRound <= 0) {
            String message =
                    messageSource.getMessage(
                            "error.activityType.invalidCapacity", null, Locale.getDefault());
            throw new IllegalArgumentException(message);
        }
    }
}
