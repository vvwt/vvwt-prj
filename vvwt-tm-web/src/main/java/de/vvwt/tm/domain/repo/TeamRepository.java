package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.Team;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
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

    /**
     * Returns all teams in the given tournament that belong to the active tenant.
     *
     * <p>Used by the {@link de.vvwt.tm.domain.referee.RefereeAssigner} to load the full
     * set of teams from which eligible referees are selected.
     *
     * @param tournamentId the tournament to query
     * @return list of teams in the given tournament for the active tenant; never {@code null}
     * @throws IllegalStateException if no tenant context is active
     */
    public List<Team> findByTournamentId(UUID tournamentId) {
        UUID tenantId = activeTenantId();  // guard fires here — before any SQL
        List<Team> result = new ArrayList<>();
        for (Team team : delegate.findByTournamentIdRaw(tournamentId)) {
            if (tenantId.equals(team.getTenantId())) {
                result.add(team);
            }
        }
        return result;
    }
}
