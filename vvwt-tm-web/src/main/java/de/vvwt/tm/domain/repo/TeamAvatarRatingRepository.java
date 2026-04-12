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
     * Returns all avatar ratings for avatars in the given phase, scoped to the active tenant.
     *
     * <p>Used by the round-end snapshot service (E03S13) to load standing rows for payload
     * generation. The results are sorted by the caller per D-33.
     *
     * @param phaseId the phase whose avatar ratings are to be loaded
     * @return list of tenant-scoped ratings for avatars in the phase; never {@code null}
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
