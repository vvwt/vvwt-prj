package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.Match;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Tenant-scoped repository for {@link Match} entities.
 *
 * <p>Provides the domain-specific query {@link #findByPhaseId(UUID)} required by the
 * cascade service (E03S11).
 *
 * @see TenantScopedRepository
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E03S05.story.md">Story E03S05</a>
 */
@Repository
public class MatchRepository extends TenantScopedRepository<Match, UUID> {

    private final MatchCrudRepository delegate;
    private final TenantContext tenantContext;
    private final JdbcAggregateOperations jdbcOps;

    public MatchRepository(MatchCrudRepository delegate,
                            TenantContext tenantContext,
                            JdbcAggregateOperations jdbcOps) {
        this.delegate = delegate;
        this.tenantContext = tenantContext;
        this.jdbcOps = jdbcOps;
    }

    @Override protected CrudRepository<Match, UUID> delegate() { return delegate; }
    @Override protected TenantContext tenantContext() { return tenantContext; }
    @Override protected JdbcAggregateOperations jdbcOperations() { return jdbcOps; }
    @Override protected UUID extractTenantId(Match e) { return e.getTenantId(); }
    @Override protected void setTenantId(Match e, UUID id) { e.setTenantId(id); }
    @Override protected UUID extractId(Match e) { return e.getId(); }

    /**
     * Returns all matches in the given phase that belong to the active tenant.
     *
     * @param phaseId the phase to query
     * @return list of matches in the given phase for the active tenant; never {@code null}
     * @throws IllegalStateException if no tenant context is active
     */
    public List<Match> findByPhaseId(UUID phaseId) {
        UUID tenantId = activeTenantId();  // guard fires here — before any SQL
        List<Match> result = new ArrayList<>();
        for (Match match : delegate.findByPhaseIdRaw(phaseId)) {
            if (tenantId.equals(match.getTenantId())) {
                result.add(match);
            }
        }
        return result;
    }

    /**
     * Returns all terminal matches for a given avatar in a given phase, scoped to the active tenant.
     *
     * <p>Used by cascade steps 7–8 (E03S11, AC9/AC10) for {@code refreshAvatarRating}.
     * Terminal states: FINISHED_STANDOFF (50), FINISHED_WINNER1 (51), FINISHED_WINNER2 (52).
     *
     * @param phaseId  the phase whose matches are queried
     * @param avatarId the team avatar — matches where this avatar appears in slot 1 or slot 2 are returned
     * @return list of terminal matches for the avatar in the phase; never {@code null}
     * @throws IllegalStateException if no tenant context is active
     */
    public List<Match> findTerminalByPhaseIdAndAvatarId(UUID phaseId, UUID avatarId) {
        UUID tenantId = activeTenantId();  // guard fires here — before any SQL
        List<Match> result = new ArrayList<>();
        for (Match match : delegate.findTerminalByPhaseIdAndAvatarIdRaw(phaseId, avatarId)) {
            if (tenantId.equals(match.getTenantId())) {
                result.add(match);
            }
        }
        return result;
    }
}
