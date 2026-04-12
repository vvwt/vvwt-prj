package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.TeamAvatar;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Tenant-scoped repository for {@link TeamAvatar} entities.
 *
 * <p>Provides the domain-specific query {@link #findByPhaseId(UUID)} required by the
 * match generator (E03S09).
 *
 * @see TenantScopedRepository
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E03S05.story.md">Story E03S05</a>
 */
@Repository
public class TeamAvatarRepository extends TenantScopedRepository<TeamAvatar, UUID> {

    private final TeamAvatarCrudRepository delegate;
    private final TenantContext tenantContext;
    private final JdbcAggregateOperations jdbcOps;

    public TeamAvatarRepository(TeamAvatarCrudRepository delegate,
                                  TenantContext tenantContext,
                                  JdbcAggregateOperations jdbcOps) {
        this.delegate = delegate;
        this.tenantContext = tenantContext;
        this.jdbcOps = jdbcOps;
    }

    @Override protected CrudRepository<TeamAvatar, UUID> delegate() { return delegate; }
    @Override protected TenantContext tenantContext() { return tenantContext; }
    @Override protected JdbcAggregateOperations jdbcOperations() { return jdbcOps; }
    @Override protected UUID extractTenantId(TeamAvatar e) { return e.getTenantId(); }
    @Override protected void setTenantId(TeamAvatar e, UUID id) { e.setTenantId(id); }
    @Override protected UUID extractId(TeamAvatar e) { return e.getId(); }

    /**
     * Returns all {@link TeamAvatar}s in the given phase that belong to the active tenant.
     *
     * @param phaseId the phase to query
     * @return list of team avatars in the given phase for the active tenant; never {@code null}
     * @throws IllegalStateException if no tenant context is active
     */
    public List<TeamAvatar> findByPhaseId(UUID phaseId) {
        UUID tenantId = activeTenantId();  // guard fires here — before any SQL
        List<TeamAvatar> result = new ArrayList<>();
        for (TeamAvatar avatar : delegate.findByPhaseIdRaw(phaseId)) {
            if (tenantId.equals(avatar.getTenantId())) {
                result.add(avatar);
            }
        }
        return result;
    }
}
