package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.Phase;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

/**
 * Tenant-scoped repository for {@link Phase} entities.
 *
 * @see TenantScopedRepository
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E03S05.story.md">Story
 *     E03S05</a>
 */
@Repository
public class PhaseRepository extends TenantScopedRepository<Phase, UUID> {

    private final PhaseCrudRepository delegate;
    private final TenantContext tenantContext;
    private final JdbcAggregateOperations jdbcOps;

    public PhaseRepository(
            PhaseCrudRepository delegate,
            TenantContext tenantContext,
            JdbcAggregateOperations jdbcOps) {
        this.delegate = delegate;
        this.tenantContext = tenantContext;
        this.jdbcOps = jdbcOps;
    }

    @Override
    protected CrudRepository<Phase, UUID> delegate() {
        return delegate;
    }

    @Override
    protected TenantContext tenantContext() {
        return tenantContext;
    }

    @Override
    protected JdbcAggregateOperations jdbcOperations() {
        return jdbcOps;
    }

    @Override
    protected UUID extractTenantId(Phase e) {
        return e.getTenantId();
    }

    @Override
    protected void setTenantId(Phase e, UUID id) {
        e.setTenantId(id);
    }

    @Override
    protected UUID extractId(Phase e) {
        return e.getId();
    }

    /**
     * Returns all phases belonging to the given tournament that belong to the active tenant.
     *
     * <p>Used by {@link de.vvwt.tm.domain.TournamentService} to check whether a tournament has
     * associated phases before allowing deletion (E05S04 AC5).
     *
     * @param tournamentId the tournament to query
     * @return list of phases for the given tournament scoped to the active tenant; never {@code
     *     null}
     * @throws IllegalStateException if no tenant context is active
     */
    public List<Phase> findByTournamentId(UUID tournamentId) {
        UUID tenantId = activeTenantId(); // guard fires here — before any SQL
        List<Phase> result = new ArrayList<>();
        for (Phase phase : delegate.findByTournamentIdRaw(tournamentId)) {
            if (tenantId.equals(phase.getTenantId())) {
                result.add(phase);
            }
        }
        return result;
    }
}
