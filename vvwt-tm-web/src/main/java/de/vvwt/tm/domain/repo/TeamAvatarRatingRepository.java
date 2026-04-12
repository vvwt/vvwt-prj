package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.TeamAvatarRating;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
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

    /**
     * Returns all ratings for the given phase that belong to the active tenant.
     *
     * <p>Used by {@link de.vvwt.tm.domain.LiveMonitoringService} (E05S10) to compute group
     * tables on read (D-24: no materialized group_table table).
     *
     * @param phaseId the phase to query
     * @return list of ratings for all avatars in the phase for the active tenant; never {@code null}
     * @throws IllegalStateException if no tenant context is active
     */
    public List<TeamAvatarRating> findByPhaseId(UUID phaseId) {
        UUID tenantId = activeTenantId();  // guard fires here — before any SQL
        List<TeamAvatarRating> result = new ArrayList<>();
        for (TeamAvatarRating rating : delegate.findByPhaseIdRaw(phaseId)) {
            if (tenantId.equals(rating.getTenantId())) {
                result.add(rating);
            }
        }
        return result;
    }
}
