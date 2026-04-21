package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.ActivityType;
import de.vvwt.tm.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.stereotype.Repository;

/**
 * Tenant-scoped repository for {@link ActivityType} entities (E08S02, AC2, AC7).
 *
 * <p>All operations enforce tenant context via the active {@link TenantContext}. A call without an
 * active tenant binding throws {@link IllegalStateException} before any SQL is executed.
 *
 * <p><b>E21S13 cutover note:</b> The legacy {@code TenantScopedRepository} abstract base class
 * (deleted in the E21S13 atomic cutover alongside {@code domain.repo.TenantContext} and {@code
 * domain.repo.TenantScopedRepository}) is replaced here by inlining the required behaviour
 * directly. This is a non-DEC-32-governed DEC-22 refactor phase fix — pure behaviour preservation,
 * no semantic change. {@link TenantContext} is now sourced from {@code de.vvwt.tm.tenant
 * .TenantContext} (the reconstructed tenant context API), which provides the same {@code current()}
 * semantics as the bridge {@code domain.repo.TenantContext.getTenantId()} previously delegated to.
 *
 * @see <a href="E08S02">E08S02 — ActivityType persistence</a>
 * @see <a href="E21S13">E21S13 — Atomic cutover (DEC-32 carve-out + DEC-22 refactor fixes)</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law (refactor phase)</a>
 */
@Repository
public class ActivityTypeRepository {

    private final ActivityTypeCrudRepository delegate;
    private final TenantContext tenantContext;
    private final JdbcAggregateOperations jdbcOps;

    public ActivityTypeRepository(
            ActivityTypeCrudRepository delegate,
            TenantContext tenantContext,
            JdbcAggregateOperations jdbcOps) {
        this.delegate = delegate;
        this.tenantContext = tenantContext;
        this.jdbcOps = jdbcOps;
    }

    // -------------------------------------------------------------------------
    // Guard helper
    // -------------------------------------------------------------------------

    /**
     * Returns the active tenant ID, or throws {@link IllegalStateException} if no tenant context is
     * active. All repository methods call this before any database operation.
     *
     * @return the active tenant ID; never {@code null}
     * @throws IllegalStateException if no tenant context is active
     */
    private UUID activeTenantId() {
        return tenantContext.current();
    }

    // -------------------------------------------------------------------------
    // Read operations
    // -------------------------------------------------------------------------

    /**
     * Returns all activity types belonging to the active tenant.
     *
     * @return list of all activity types for the active tenant; never {@code null}
     * @throws IllegalStateException if no tenant context is active
     */
    public List<ActivityType> findAll() {
        UUID tenantId = activeTenantId();
        List<ActivityType> results = new ArrayList<>();
        for (ActivityType entity : delegate.findAll()) {
            if (tenantId.equals(entity.getTenantId())) {
                results.add(entity);
            }
        }
        return results;
    }

    /**
     * Finds an activity type by its primary key, scoped to the active tenant.
     *
     * @param id the primary key
     * @return the entity, or {@link Optional#empty()} if not found or belongs to a different tenant
     * @throws IllegalStateException if no tenant context is active
     */
    public Optional<ActivityType> findById(UUID id) {
        UUID tenantId = activeTenantId();
        return delegate.findById(id).filter(entity -> tenantId.equals(entity.getTenantId()));
    }

    /**
     * Saves an activity type, enforcing tenant scope.
     *
     * <p>Uses {@link JdbcAggregateOperations#insert} or {@link JdbcAggregateOperations#update}
     * directly, bypassing Spring Data JDBC's isNew() null-ID check (entities carry pre-assigned
     * UUIDs).
     *
     * @param entity the entity to save
     * @return the saved entity
     * @throws IllegalStateException if no tenant context is active
     * @throws IllegalArgumentException if the entity's tenantId does not match the active tenant
     */
    public ActivityType save(ActivityType entity) {
        UUID activeTenant = activeTenantId();
        UUID entityTenant = entity.getTenantId();
        if (entityTenant != null && !activeTenant.equals(entityTenant)) {
            throw new IllegalArgumentException(
                    "Tenant spoof rejected: entity carries tenantId="
                            + entityTenant
                            + " but the active TenantContext is tenantId="
                            + activeTenant);
        }
        if (entityTenant == null) {
            entity.setTenantId(activeTenant);
        }
        UUID id = entity.getId();
        if (id != null && delegate.existsById(id)) {
            return jdbcOps.update(entity);
        } else {
            return jdbcOps.insert(entity);
        }
    }

    /**
     * Deletes an activity type by its primary key, scoped to the active tenant.
     *
     * @param id the primary key
     * @throws IllegalStateException if no tenant context is active
     */
    public void deleteById(UUID id) {
        activeTenantId(); // guard fires before any SQL
        delegate.deleteById(id);
    }

    // -------------------------------------------------------------------------
    // Domain-specific query methods
    // -------------------------------------------------------------------------

    /**
     * Returns all activity types for the given tournament that belong to the active tenant, ordered
     * by {@code sort_order} ascending.
     *
     * @param tournamentId the tournament to query
     * @return list of activity types for the given tournament scoped to the active tenant; never
     *     {@code null}
     * @throws IllegalStateException if no tenant context is active
     */
    public List<ActivityType> findByTournamentId(UUID tournamentId) {
        UUID tenantId = activeTenantId(); // guard fires here — before any SQL
        List<ActivityType> result = new ArrayList<>();
        for (ActivityType at : delegate.findByTournamentIdRaw(tournamentId)) {
            if (tenantId.equals(at.getTenantId())) {
                result.add(at);
            }
        }
        result.sort(java.util.Comparator.comparingInt(ActivityType::getSortOrder));
        return result;
    }

    /**
     * Returns {@code true} if an activity type with the given name already exists for the specified
     * tournament in the active tenant's scope (AC5 duplicate-name check).
     *
     * @param tournamentId the tournament to check
     * @param name the activity name to check
     * @return {@code true} if a duplicate exists
     * @throws IllegalStateException if no tenant context is active
     */
    public boolean existsByTournamentIdAndName(UUID tournamentId, String name) {
        return findByTournamentId(tournamentId).stream().anyMatch(at -> name.equals(at.getName()));
    }
}
