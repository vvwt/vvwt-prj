package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.Team;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

/**
 * Tenant-scoped repository for {@link Team} entities.
 *
 * @see TenantScopedRepository
 * @see <a
 *     href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E03S05.story.md">Story
 *     E03S05</a>
 */
@Repository
public class TeamRepository extends TenantScopedRepository<Team, UUID> {

    private final TeamCrudRepository delegate;
    private final TenantContext tenantContext;
    private final JdbcAggregateOperations jdbcOps;

    public TeamRepository(
            TeamCrudRepository delegate,
            TenantContext tenantContext,
            JdbcAggregateOperations jdbcOps) {
        this.delegate = delegate;
        this.tenantContext = tenantContext;
        this.jdbcOps = jdbcOps;
    }

    @Override
    protected CrudRepository<Team, UUID> delegate() {
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
    protected UUID extractTenantId(Team e) {
        return e.getTenantId();
    }

    @Override
    protected void setTenantId(Team e, UUID id) {
        e.setTenantId(id);
    }

    @Override
    protected UUID extractId(Team e) {
        return e.getId();
    }

    /**
     * Returns all teams in the given tournament that belong to the active tenant, ordered by
     * team_number ascending (AC1 — E05S05).
     *
     * <p>Used by the {@link de.vvwt.tm.domain.referee.RefereeAssigner} to load the full set of
     * teams from which eligible referees are selected.
     *
     * @param tournamentId the tournament to query
     * @return list of teams in the given tournament for the active tenant; never {@code null}
     * @throws IllegalStateException if no tenant context is active
     */
    public List<Team> findByTournamentId(UUID tournamentId) {
        UUID tenantId = activeTenantId(); // guard fires here — before any SQL
        List<Team> result = new ArrayList<>();
        for (Team team : delegate.findByTournamentIdRaw(tournamentId)) {
            if (tenantId.equals(team.getTenantId())) {
                result.add(team);
            }
        }
        return result;
    }

    /**
     * Returns whether the given team has any {@code TeamAvatar} references (AC4 — E05S05).
     *
     * <p>A team with avatar references cannot be deleted (DEC-9 structural identity is established
     * via TeamAvatars; once avatars exist, deleting the source team would orphan them).
     *
     * @param teamId the team UUID
     * @return {@code true} if at least one TeamAvatar references this team
     */
    public boolean hasTeamAvatars(UUID teamId) {
        return delegate.countAvatarsByTeamId(teamId) > 0;
    }

    /**
     * Returns the next available team number for the given tournament (AC2 — E05S05).
     *
     * <p>The auto-assigned team number is max(existing) + 1, or 1 if no teams exist yet.
     *
     * @param tournamentId the tournament to query
     * @return the next team number (≥ 1)
     */
    public int nextTeamNumber(UUID tournamentId) {
        return delegate.findMaxTeamNumberByTournamentId(tournamentId) + 1;
    }

    /**
     * Returns whether a team with the given team_number already exists in the tournament, excluding
     * the team identified by {@code excludeId} (AC10 — E05S05).
     *
     * <p>Pass a zero UUID as {@code excludeId} when checking for a new (not yet persisted) team.
     *
     * @param tournamentId the tournament scope
     * @param teamNumber the team number to check
     * @param excludeId the team ID to exclude from the count (zero UUID for new teams)
     * @return {@code true} if another team already uses this number in the tournament
     */
    public boolean teamNumberExists(UUID tournamentId, int teamNumber, UUID excludeId) {
        return delegate.countByTournamentIdAndTeamNumberExcluding(
                        tournamentId, teamNumber, excludeId)
                > 0;
    }
}
