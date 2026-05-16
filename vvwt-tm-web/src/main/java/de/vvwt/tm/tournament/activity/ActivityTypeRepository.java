// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.activity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Custom repository interface for {@link ActivityType} persistence (DEC-35 hexagonal-pragma).
 *
 * <p>This interface is the public Modulith API surface for {@code tournament.activity} persistence.
 * The canonical implementation is {@link
 * de.vvwt.tm.tournament.activity.internal.DefaultActivityTypeRepository}.
 *
 * <p>All operations enforce tenant context via the active {@link de.vvwt.tm.tenant.TenantContext}.
 * A call without an active tenant binding throws {@link IllegalStateException} before any SQL is
 * executed.
 *
 * <p><b>E45S01 relocation note:</b> Extracted as interface from the concrete {@code
 * de.vvwt.tm.domain.repo.ActivityTypeRepository} class per DEC-35 hexagonal-pragma (hand-authored
 * interface = port; concrete impl in {@code .internal}). {@code ActivityTypeCrudRepository} moves
 * to {@code internal} as the Spring Data JDBC delegate.
 *
 * @see ActivityType
 */
public interface ActivityTypeRepository {

    /**
     * Returns all activity types belonging to the active tenant.
     *
     * @return list of all activity types for the active tenant; never {@code null}
     * @throws IllegalStateException if no tenant context is active
     */
    List<ActivityType> findAll();

    /**
     * Finds an activity type by its primary key, scoped to the active tenant.
     *
     * @param id the primary key
     * @return the entity, or {@link Optional#empty()} if not found or belongs to a different tenant
     * @throws IllegalStateException if no tenant context is active
     */
    Optional<ActivityType> findById(UUID id);

    /**
     * Saves an activity type, enforcing tenant scope.
     *
     * @param entity the entity to save
     * @return the saved entity
     * @throws IllegalStateException if no tenant context is active
     * @throws IllegalArgumentException if the entity's tenantId does not match the active tenant
     */
    ActivityType save(ActivityType entity);

    /**
     * Deletes an activity type by its primary key, scoped to the active tenant.
     *
     * @param id the primary key
     * @throws IllegalStateException if no tenant context is active
     */
    void deleteById(UUID id);

    /**
     * Returns all activity types for the given tournament that belong to the active tenant, ordered
     * by {@code sort_order} ascending.
     *
     * @param tournamentId the tournament to query
     * @return list of activity types for the given tournament scoped to the active tenant; never
     *     {@code null}
     * @throws IllegalStateException if no tenant context is active
     */
    List<ActivityType> findByTournamentId(UUID tournamentId);

    /**
     * Returns {@code true} if an activity type with the given name already exists for the specified
     * tournament in the active tenant's scope (AC5 duplicate-name check).
     *
     * @param tournamentId the tournament to check
     * @param name the activity name to check
     * @return {@code true} if a duplicate exists
     * @throws IllegalStateException if no tenant context is active
     */
    boolean existsByTournamentIdAndName(UUID tournamentId, String name);
}
