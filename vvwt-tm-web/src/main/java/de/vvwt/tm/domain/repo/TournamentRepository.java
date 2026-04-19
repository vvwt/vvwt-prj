package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.Tournament;
import java.util.UUID;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

/**
 * Tenant-scoped repository for {@link Tournament} entities.
 *
 * @see TenantScopedRepository
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E03S05.story.md">Story
 *     E03S05</a>
 */
@Repository
public class TournamentRepository extends TenantScopedRepository<Tournament, UUID> {

    private final TournamentCrudRepository delegate;
    private final TenantContext tenantContext;
    private final JdbcAggregateOperations jdbcOps;

    public TournamentRepository(
            TournamentCrudRepository delegate,
            TenantContext tenantContext,
            JdbcAggregateOperations jdbcOps) {
        this.delegate = delegate;
        this.tenantContext = tenantContext;
        this.jdbcOps = jdbcOps;
    }

    @Override
    protected CrudRepository<Tournament, UUID> delegate() {
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
    protected UUID extractTenantId(Tournament e) {
        return e.getTenantId();
    }

    @Override
    protected void setTenantId(Tournament e, UUID id) {
        e.setTenantId(id);
    }

    @Override
    protected UUID extractId(Tournament e) {
        return e.getId();
    }
}
