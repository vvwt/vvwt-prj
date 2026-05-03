package de.vvwt.tm.tournament.activity.internal;

import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tournament.activity.ActivityType;
import de.vvwt.tm.tournament.activity.ActivityTypeRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.stereotype.Repository;

/**
 * Default implementation of {@link ActivityTypeRepository} (DEC-35 naming canon).
 *
 * <p>Tenant-scoped repository for {@link ActivityType} entities (E08S02, AC2, AC7). All operations
 * enforce tenant context via the active {@link TenantContext}. A call without an active tenant
 * binding throws {@link IllegalStateException} before any SQL is executed.
 *
 * <p><b>E45S01 relocation note:</b> Relocated and refactored from {@code
 * de.vvwt.tm.domain.repo.ActivityTypeRepository} into DEC-35 hexagonal-pragma layout: public
 * interface {@link ActivityTypeRepository} in {@code tournament.activity}; this implementation in
 * {@code tournament.activity.internal}.
 *
 * @see ActivityTypeRepository
 */
@Repository
public class DefaultActivityTypeRepository implements ActivityTypeRepository {

    private final ActivityTypeCrudRepository delegate;
    private final TenantContext tenantContext;
    private final JdbcAggregateOperations jdbcOps;

    public DefaultActivityTypeRepository(
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
     * active.
     */
    private UUID activeTenantId() {
        return tenantContext.current();
    }

    // -------------------------------------------------------------------------
    // Read operations
    // -------------------------------------------------------------------------

    @Override
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

    @Override
    public Optional<ActivityType> findById(UUID id) {
        UUID tenantId = activeTenantId();
        return delegate.findById(id).filter(entity -> tenantId.equals(entity.getTenantId()));
    }

    @Override
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

    @Override
    public void deleteById(UUID id) {
        activeTenantId(); // guard fires before any SQL
        delegate.deleteById(id);
    }

    @Override
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

    @Override
    public boolean existsByTournamentIdAndName(UUID tournamentId, String name) {
        return findByTournamentId(tournamentId).stream().anyMatch(at -> name.equals(at.getName()));
    }
}
