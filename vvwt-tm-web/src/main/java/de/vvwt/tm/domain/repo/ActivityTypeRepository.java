package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.ActivityType;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Tenant-scoped repository for {@link ActivityType} entities (E08S02, AC2, AC7).
 *
 * <p>All operations enforce tenant context via the {@link TenantScopedRepository} guard.
 * A call without an active {@link TenantContext} throws {@link IllegalStateException}
 * before any SQL is executed.
 *
 * @see TenantScopedRepository
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E08S02.story.md">Story E08S02</a>
 */
@Repository
public class ActivityTypeRepository extends TenantScopedRepository<ActivityType, UUID> {

    private final ActivityTypeCrudRepository delegate;
    private final TenantContext tenantContext;
    private final JdbcAggregateOperations jdbcOps;

    public ActivityTypeRepository(ActivityTypeCrudRepository delegate,
                                   TenantContext tenantContext,
                                   JdbcAggregateOperations jdbcOps) {
        this.delegate = delegate;
        this.tenantContext = tenantContext;
        this.jdbcOps = jdbcOps;
    }

    @Override protected CrudRepository<ActivityType, UUID> delegate() { return delegate; }
    @Override protected TenantContext tenantContext() { return tenantContext; }
    @Override protected JdbcAggregateOperations jdbcOperations() { return jdbcOps; }
    @Override protected UUID extractTenantId(ActivityType e) { return e.getTenantId(); }
    @Override protected void setTenantId(ActivityType e, UUID id) { e.setTenantId(id); }
    @Override protected UUID extractId(ActivityType e) { return e.getId(); }

    /**
     * Returns all activity types for the given tournament that belong to the active tenant,
     * ordered by {@code sort_order} ascending.
     *
     * @param tournamentId the tournament to query
     * @return list of activity types for the given tournament scoped to the active tenant;
     *         never {@code null}
     * @throws IllegalStateException if no tenant context is active
     */
    public List<ActivityType> findByTournamentId(UUID tournamentId) {
        UUID tenantId = activeTenantId();  // guard fires here — before any SQL
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
     * Returns {@code true} if an activity type with the given name already exists for the
     * specified tournament in the active tenant's scope (AC5 duplicate-name check).
     *
     * @param tournamentId the tournament to check
     * @param name         the activity name to check
     * @return {@code true} if a duplicate exists
     * @throws IllegalStateException if no tenant context is active
     */
    public boolean existsByTournamentIdAndName(UUID tournamentId, String name) {
        return findByTournamentId(tournamentId).stream()
                .anyMatch(at -> name.equals(at.getName()));
    }
}
