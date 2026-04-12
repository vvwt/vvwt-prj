package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.Phase;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Tenant-scoped repository for {@link Phase} entities.
 *
 * @see TenantScopedRepository
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E03S05.story.md">Story E03S05</a>
 */
@Repository
public class PhaseRepository extends TenantScopedRepository<Phase, UUID> {

    private final PhaseCrudRepository delegate;
    private final TenantContext tenantContext;
    private final JdbcAggregateOperations jdbcOps;

    public PhaseRepository(PhaseCrudRepository delegate,
                            TenantContext tenantContext,
                            JdbcAggregateOperations jdbcOps) {
        this.delegate = delegate;
        this.tenantContext = tenantContext;
        this.jdbcOps = jdbcOps;
    }

    @Override protected CrudRepository<Phase, UUID> delegate() { return delegate; }
    @Override protected TenantContext tenantContext() { return tenantContext; }
    @Override protected JdbcAggregateOperations jdbcOperations() { return jdbcOps; }
    @Override protected UUID extractTenantId(Phase e) { return e.getTenantId(); }
    @Override protected void setTenantId(Phase e, UUID id) { e.setTenantId(id); }
    @Override protected UUID extractId(Phase e) { return e.getId(); }
}
