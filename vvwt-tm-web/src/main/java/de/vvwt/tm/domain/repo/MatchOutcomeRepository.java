package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.MatchOutcome;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Tenant-scoped repository for {@link MatchOutcome} entities.
 *
 * <p>{@link MatchOutcome} uses {@code matchId} as its {@code @Id} (1:1 with match).
 *
 * @see TenantScopedRepository
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E03S05.story.md">Story E03S05</a>
 */
@Repository
public class MatchOutcomeRepository extends TenantScopedRepository<MatchOutcome, UUID> {

    private final MatchOutcomeCrudRepository delegate;
    private final TenantContext tenantContext;
    private final JdbcAggregateOperations jdbcOps;

    public MatchOutcomeRepository(MatchOutcomeCrudRepository delegate,
                                    TenantContext tenantContext,
                                    JdbcAggregateOperations jdbcOps) {
        this.delegate = delegate;
        this.tenantContext = tenantContext;
        this.jdbcOps = jdbcOps;
    }

    @Override protected CrudRepository<MatchOutcome, UUID> delegate() { return delegate; }
    @Override protected TenantContext tenantContext() { return tenantContext; }
    @Override protected JdbcAggregateOperations jdbcOperations() { return jdbcOps; }
    @Override protected UUID extractTenantId(MatchOutcome e) { return e.getTenantId(); }
    @Override protected void setTenantId(MatchOutcome e, UUID id) { e.setTenantId(id); }
    @Override protected UUID extractId(MatchOutcome e) { return e.getMatchId(); }
}
