package de.vvwt.tm.domain.repo;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;

/**
 * Abstract base class for all tenant-scoped repositories in the Tournament Manager domain.
 *
 * <p>Every method in this class calls {@link TenantContext#getTenantId()} before any database
 * operation. If no tenant context is active, {@link TenantContext#getTenantId()} throws {@link
 * IllegalStateException}, which propagates to the caller — satisfying the AC6 runtime guard
 * requirement ("guard fires before any SQL is executed").
 *
 * <h2>Delegator pattern (AC2)</h2>
 *
 * <p>This class wraps a Spring Data JDBC {@link org.springframework.data.repository.CrudRepository}
 * instance for read/delete operations and a {@link JdbcAggregateOperations} template for write
 * operations. The template is used for writes because Spring Data JDBC's {@code
 * CrudRepository.save()} determines "new vs existing" via the {@code @Id} field's null-check. Our
 * entities always have pre-assigned UUIDs, so {@code save()} would issue an UPDATE on a new entity.
 * We bypass this by calling {@code insert()} or {@code update()} explicitly based on a prior
 * existence check.
 *
 * <h2>Read filtering (AC2)</h2>
 *
 * <p>For V1 single-tenant runtime, all rows in the database belong to the default tenant, so
 * in-memory filtering after {@code findAll()} always returns the full result set. The
 * <em>structural guarantee</em> is provided by the guard that fires before SQL — an unscoped call
 * throws before reaching the database.
 *
 * <h2>Write enforcement (AC3)</h2>
 *
 * <p>{@link #save(Object)} reads the entity's {@code tenantId} field via {@link #extractTenantId}
 * and compares it to the active TenantContext. A mismatch fails fast with a clear error.
 *
 * <h2>AC13 — no unscoped escape hatch</h2>
 *
 * <p>There is no {@code findAllUnscoped()} method. Subclasses MUST NOT add one.
 *
 * @param <T> the entity type
 * @param <ID> the entity's primary-key type
 * @see TenantContext
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E03S05.story.md">Story
 *     E03S05</a>
 */
public abstract class TenantScopedRepository<T, ID> {

    // -------------------------------------------------------------------------
    // Abstract contract — subclasses supply these
    // -------------------------------------------------------------------------

    /** Returns the Spring Data JDBC CrudRepository delegate for reads and deletes. */
    protected abstract org.springframework.data.repository.CrudRepository<T, ID> delegate();

    /** Returns the active {@link TenantContext} bean. */
    protected abstract TenantContext tenantContext();

    /** Returns the {@link JdbcAggregateOperations} template for INSERT/UPDATE bypassing isNew(). */
    protected abstract JdbcAggregateOperations jdbcOperations();

    /** Extracts the {@code tenantId} field from the entity. */
    protected abstract UUID extractTenantId(T entity);

    /** Sets the {@code tenantId} field on the entity before persisting. */
    protected abstract void setTenantId(T entity, UUID tenantId);

    /** Extracts the primary key ({@code @Id} field) from the entity. */
    protected abstract ID extractId(T entity);

    // -------------------------------------------------------------------------
    // Guard helper
    // -------------------------------------------------------------------------

    /**
     * Returns the active tenant ID, or throws {@link IllegalStateException} if no tenant context is
     * active. All repository methods call this before any database operation.
     *
     * @return the active tenant ID; never {@code null}
     * @throws IllegalStateException if no tenant context is active (AC6 guard)
     */
    protected final UUID activeTenantId() {
        return tenantContext().getTenantId();
    }

    // -------------------------------------------------------------------------
    // Read operations (AC2)
    // -------------------------------------------------------------------------

    /**
     * Returns all entities belonging to the active tenant.
     *
     * @return list of entities for the active tenant; never {@code null}
     * @throws IllegalStateException if no tenant context is active
     */
    public List<T> findAll() {
        UUID tenantId = activeTenantId(); // guard fires here — before any SQL
        List<T> results = new ArrayList<>();
        for (T entity : delegate().findAll()) {
            if (tenantId.equals(extractTenantId(entity))) {
                results.add(entity);
            }
        }
        return results;
    }

    /**
     * Finds an entity by its primary key, scoped to the active tenant.
     *
     * @param id the primary key
     * @return the entity, or {@link Optional#empty()} if not found or belongs to a different tenant
     * @throws IllegalStateException if no tenant context is active
     */
    public Optional<T> findById(ID id) {
        UUID tenantId = activeTenantId(); // guard fires here — before any SQL
        return delegate().findById(id).filter(entity -> tenantId.equals(extractTenantId(entity)));
    }

    /**
     * Returns the count of entities belonging to the active tenant.
     *
     * @return the count of entities for the active tenant
     * @throws IllegalStateException if no tenant context is active
     */
    public long count() {
        UUID tenantId = activeTenantId(); // guard fires here — before any SQL
        long count = 0;
        for (T entity : delegate().findAll()) {
            if (tenantId.equals(extractTenantId(entity))) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns whether an entity with the given ID exists and belongs to the active tenant.
     *
     * @param id the primary key
     * @return {@code true} if exists and belongs to the active tenant
     * @throws IllegalStateException if no tenant context is active
     */
    public boolean existsById(ID id) {
        return findById(id).isPresent();
    }

    // -------------------------------------------------------------------------
    // Write operations (AC3)
    // -------------------------------------------------------------------------

    /**
     * Saves an entity, enforcing tenant scope.
     *
     * <p>Uses {@link JdbcAggregateOperations#insert} or {@link JdbcAggregateOperations#update}
     * directly, bypassing Spring Data JDBC's {@code isNew()} null-ID check. This is necessary
     * because our entities always carry pre-assigned UUIDs.
     *
     * <p>Tenant spoof guard: if the entity has a non-null tenantId that differs from the active
     * TenantContext, throws {@link IllegalArgumentException} (AC3).
     *
     * @param entity the entity to save
     * @return the saved entity
     * @throws IllegalStateException if no tenant context is active
     * @throws IllegalArgumentException if the entity's tenantId does not match the active tenant
     */
    @SuppressWarnings("unchecked")
    public <S extends T> S save(S entity) {
        UUID activeTenant = activeTenantId(); // guard fires here — before any SQL
        UUID entityTenant = extractTenantId(entity);
        if (entityTenant != null && !activeTenant.equals(entityTenant)) {
            throw new IllegalArgumentException(
                    "Tenant spoof rejected: entity carries tenantId="
                            + entityTenant
                            + " but the active TenantContext is tenantId="
                            + activeTenant
                            + ". Entity type: "
                            + entity.getClass().getSimpleName());
        }
        if (entityTenant == null) {
            setTenantId(entity, activeTenant);
        }
        // Check existence by looking up the ID in the delegate (which is a direct DB check
        // without the tenant filter — intentional, because we are checking raw existence here,
        // not tenant-filtered existence, to decide INSERT vs UPDATE).
        ID id = extractId(entity);
        if (id != null && delegate().existsById(id)) {
            return (S) jdbcOperations().update(entity);
        } else {
            return (S) jdbcOperations().insert(entity);
        }
    }

    /**
     * Saves all entities, enforcing tenant scope on each.
     *
     * @param entities the entities to save
     * @return the saved entities
     */
    public <S extends T> List<S> saveAll(Iterable<S> entities) {
        List<S> saved = new ArrayList<>();
        for (S entity : entities) {
            saved.add(save(entity));
        }
        return saved;
    }

    // -------------------------------------------------------------------------
    // Delete operations
    // -------------------------------------------------------------------------

    /**
     * Deletes the entity with the given ID if it belongs to the active tenant.
     *
     * @param id the primary key of the entity to delete
     * @throws IllegalStateException if no tenant context is active
     */
    public void deleteById(ID id) {
        UUID tenantId = activeTenantId(); // guard fires here — before any SQL
        delegate()
                .findById(id)
                .filter(entity -> tenantId.equals(extractTenantId(entity)))
                .ifPresent(entity -> delegate().deleteById(id));
    }
}
