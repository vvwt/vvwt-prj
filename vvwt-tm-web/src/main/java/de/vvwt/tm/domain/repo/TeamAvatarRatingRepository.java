package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.TeamAvatarRating;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Tenant-scoped repository for {@link TeamAvatarRating} entities.
 *
 * <p>{@link TeamAvatarRating} uses {@code avatarId} as its {@code @Id} (1:1 with avatar).
 *
 * @see TenantScopedRepository
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E03S05.story.md">Story E03S05</a>
 */
@Repository
public class TeamAvatarRatingRepository extends TenantScopedRepository<TeamAvatarRating, UUID> {

    private final TeamAvatarRatingCrudRepository delegate;
    private final TenantContext tenantContext;
    private final JdbcAggregateOperations jdbcOps;

    public TeamAvatarRatingRepository(TeamAvatarRatingCrudRepository delegate,
                                       TenantContext tenantContext,
                                       JdbcAggregateOperations jdbcOps) {
        this.delegate = delegate;
        this.tenantContext = tenantContext;
        this.jdbcOps = jdbcOps;
    }

    @Override protected CrudRepository<TeamAvatarRating, UUID> delegate() { return delegate; }
    @Override protected TenantContext tenantContext() { return tenantContext; }
    @Override protected JdbcAggregateOperations jdbcOperations() { return jdbcOps; }
    @Override protected UUID extractTenantId(TeamAvatarRating e) { return e.getTenantId(); }
    @Override protected void setTenantId(TeamAvatarRating e, UUID id) { e.setTenantId(id); }
    @Override protected UUID extractId(TeamAvatarRating e) { return e.getAvatarId(); }
}
