package de.vvwt.tm.tournament.activity.internal;

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
 * <p>Tenant-scoped repository for {@link ActivityType} entities (E08S02, AC2, AC7). Under DEC-20
 * DB-per-Tenant, the RoutingTenantDataSource provides per-tenant connection isolation — no explicit
 * {@code tenant_id} column filtering is required (E45S06 DEC-50 column-discriminator removal).
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
    private final JdbcAggregateOperations jdbcOps;

    public DefaultActivityTypeRepository(
            ActivityTypeCrudRepository delegate, JdbcAggregateOperations jdbcOps) {
        this.delegate = delegate;
        this.jdbcOps = jdbcOps;
    }

    // -------------------------------------------------------------------------
    // Read operations
    // -------------------------------------------------------------------------

    @Override
    public List<ActivityType> findAll() {
        List<ActivityType> results = new ArrayList<>();
        delegate.findAll().forEach(results::add);
        return results;
    }

    @Override
    public Optional<ActivityType> findById(UUID id) {
        return delegate.findById(id);
    }

    @Override
    public ActivityType save(ActivityType entity) {
        UUID id = entity.getId();
        if (id != null && delegate.existsById(id)) {
            return jdbcOps.update(entity);
        } else {
            return jdbcOps.insert(entity);
        }
    }

    @Override
    public void deleteById(UUID id) {
        delegate.deleteById(id);
    }

    @Override
    public List<ActivityType> findByTournamentId(UUID tournamentId) {
        List<ActivityType> result = new ArrayList<>(delegate.findByTournamentIdRaw(tournamentId));
        result.sort(java.util.Comparator.comparingInt(ActivityType::getSortOrder));
        return result;
    }

    @Override
    public boolean existsByTournamentIdAndName(UUID tournamentId, String name) {
        return findByTournamentId(tournamentId).stream().anyMatch(at -> name.equals(at.getName()));
    }
}
