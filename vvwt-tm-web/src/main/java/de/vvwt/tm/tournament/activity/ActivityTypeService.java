// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.activity;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Domain service interface for {@link ActivityType} operations (E08S02, AC4–AC8).
 *
 * <p>This interface is the public Modulith API surface for the {@code tournament.activity}
 * sub-package (DEC-35 hexagonal-pragma: service interfaces in public package, implementations in
 * {@code .internal}). The canonical implementation is {@link
 * de.vvwt.tm.tournament.activity.internal.DefaultActivityTypeService}.
 *
 * <h2>Validation rules enforced by implementations</h2>
 *
 * <ul>
 *   <li>AC4: {@code assignmentRule} must be a recognized {@link AssignmentRule} value.
 *   <li>AC5: {@code name} must be unique within the tournament.
 *   <li>AC6: {@code capacityPerRound} must be {@code null} (unlimited) or &gt; 0.
 * </ul>
 *
 * <p><b>E45S01 relocation note:</b> Extracted as interface from the concrete {@code
 * de.vvwt.tm.domain.ActivityTypeService} class per DEC-35 + DEC-21 atomic-cutover. The concrete
 * service is moved to {@code internal.DefaultActivityTypeService}.
 *
 * @see ActivityType
 * @see AssignmentRule
 */
public interface ActivityTypeService {

    /**
     * Creates a new activity type for the given tournament.
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
     * @throws de.vvwt.tm.tournament.exceptions.ConflictException if a duplicate name exists for the
     *     tournament (AC5)
     * @throws IllegalStateException if no tenant context is active (AC7)
     */
    ActivityType create(
            UUID tournamentId,
            String name,
            String assignmentRule,
            Integer capacityPerRound,
            int sortOrder);

    /**
     * Updates an existing activity type for the given tournament.
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
     * @throws de.vvwt.tm.tournament.exceptions.ConflictException if a DIFFERENT activity type with
     *     the same name exists
     * @throws IllegalStateException if no tenant context is active
     */
    ActivityType update(
            UUID tournamentId,
            UUID id,
            String name,
            String assignmentRule,
            Integer capacityPerRound,
            int sortOrder);

    /**
     * Deletes an activity type for the given tournament.
     *
     * @param tournamentId the parent tournament UUID (NOT NULL)
     * @param id the activity type UUID (NOT NULL)
     * @throws NoSuchElementException if the tournament or activity type does not exist
     * @throws IllegalStateException if no tenant context is active
     */
    void delete(UUID tournamentId, UUID id);

    /**
     * Returns all activity types for the given tournament, ordered by {@code sort_order} ascending,
     * scoped to the active tenant.
     *
     * @param tournamentId the tournament UUID
     * @return list of activity types; never {@code null}; may be empty
     * @throws NoSuchElementException if the tournament does not exist for the current tenant
     * @throws IllegalStateException if no tenant context is active
     */
    List<ActivityType> findByTournamentId(UUID tournamentId);
}
