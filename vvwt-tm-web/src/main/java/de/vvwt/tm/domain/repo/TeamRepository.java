package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.Team;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Tenant-scoped repository for {@link Team} entities.
 *
 * @see TenantScopedRepository
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E03S05.story.md">Story E03S05</a>
 */
@Repository
public class TeamRepository extends TenantScopedRepository<Team, UUID> {

    private final TeamCrudRepository delegate;
    private final TenantContext tenantContext;
    private final JdbcAggregateOperations jdbcOps;

    public TeamRepository(TeamCrudRepository delegate,
                           TenantContext tenantContext,
                           JdbcAggregateOperations jdbcOps) {
        this.delegate = delegate;
        this.tenantContext = tenantContext;
        this.jdbcOps = jdbcOps;
    }

    @Override protected CrudRepository<Team, UUID> delegate() { return delegate; }
    @Override protected TenantContext tenantContext() { return tenantContext; }
    @Override protected JdbcAggregateOperations jdbcOperations() { return jdbcOps; }
    @Override protected UUID extractTenantId(Team e) { return e.getTenantId(); }
    @Override protected void setTenantId(Team e, UUID id) { e.setTenantId(id); }
    @Override protected UUID extractId(Team e) { return e.getId(); }
}
