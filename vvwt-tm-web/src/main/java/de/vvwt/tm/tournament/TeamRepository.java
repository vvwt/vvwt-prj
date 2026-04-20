package de.vvwt.tm.tournament;

import de.vvwt.tm.domain.repo.TenantContext;
import de.vvwt.tm.tournament.internal.TeamCrudRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Tenant-scoped repository for {@link Team} entities (DEC-21 public API surface).
 *
 * <p>Implements the boundary API for the {@code tournament} bounded context. With 8 importers
 * across downstream contexts (inventory line 307), this repository is the primary data-access
 * surface for the Team aggregate.
 *
 * <p>Uses plain {@link JdbcTemplate} (not Spring Data JDBC CrudRepository) to avoid entity-mapping
 * conflicts with the parallel legacy {@code de.vvwt.tm.domain.Team} entity that maps to the same
 * {@code team} table during the reconstruction-in-place phase (DEC-21/DEC-22). At the E21S13
 * atomic cutover, the legacy entity is deleted.
 *
 * <p>Tenant scoping is enforced via the active {@link TenantContext} binding for all queries.
 *
 * <p>Inventory line 307: {@code de.vvwt.tm.domain.repo.TeamRepository}.
 *
 * @see Team
 * @see TeamCrudRepository
 * @see <a href="DEC-21">DEC-21 — Spring Modulith, root package = public API surface</a>
 * @see <a href="DEC-22">DEC-22 — TDD reconstruction-in-place</a>
 * @see <a href="DEC-26">DEC-26 — DAO test governance (three rules)</a>
 * @see <a href="DEC-9">DEC-9 — TeamAvatar structural identity</a>
 * @see <a href="E21S04">E21S04 — Team aggregate reconstruction (inventory line 307)</a>
 */
@Repository("tmTeamRepository")
public class TeamRepository {

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;
    private final TeamCrudRepository crudRepository;

    private static final String INSERT_SQL =
            "INSERT INTO team (id, tenant_id, tournament_id, team_number, description,"
                    + " participate, referee_assignment, without_assessment, created_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_SQL =
            "UPDATE team SET team_number=?, description=?, participate=?,"
                    + " referee_assignment=?, without_assessment=?"
                    + " WHERE id=? AND tenant_id=?";

    private static final String SELECT_BY_ID =
            "SELECT * FROM team WHERE id=? AND tenant_id=?";

    private static final String SELECT_BY_TOURNAMENT =
            "SELECT * FROM team WHERE tournament_id=? AND tenant_id=? ORDER BY team_number ASC";

    private static final String DELETE_BY_ID = "DELETE FROM team WHERE id=? AND tenant_id=?";

    private static final String EXISTS_BY_ID =
            "SELECT COUNT(*) FROM team WHERE id=? AND tenant_id=?";

    public TeamRepository(
            JdbcTemplate jdbc,
            TenantContext tenantContext,
            TeamCrudRepository crudRepository) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
        this.crudRepository = crudRepository;
    }

    /**
     * Persists a team. Inserts if new, updates otherwise. Tenant scoping is enforced — the entity's
     * tenantId is set to the current tenant before insert.
     *
     * @param team the team to save (id must be set by caller)
     * @return the saved team
     */
    public Team save(Team team) {
        UUID currentTenantId = tenantContext.getTenantId();
        team.setTenantId(currentTenantId);

        Integer count =
                jdbc.queryForObject(EXISTS_BY_ID, Integer.class, team.getId(), currentTenantId);
        boolean exists = count != null && count > 0;

        if (exists) {
            jdbc.update(
                    UPDATE_SQL,
                    team.getTeamNumber(),
                    team.getDescription(),
                    team.isParticipate(),
                    team.isRefereeAssignment(),
                    team.isWithoutAssessment(),
                    team.getId(),
                    currentTenantId);
        } else {
            jdbc.update(
                    INSERT_SQL,
                    team.getId(),
                    currentTenantId,
                    team.getTournamentId(),
                    team.getTeamNumber(),
                    team.getDescription(),
                    team.isParticipate(),
                    team.isRefereeAssignment(),
                    team.isWithoutAssessment(),
                    team.getCreatedAt() != null ? team.getCreatedAt() : LocalDateTime.now());
        }
        return team;
    }

    /**
     * Returns the team with the given id, scoped to the current tenant.
     *
     * @param id the team UUID
     * @return Optional.of(team) if found, Optional.empty() if not found or wrong tenant
     */
    public Optional<Team> findById(UUID id) {
        UUID tenantId = tenantContext.getTenantId();
        List<Team> results = jdbc.query(SELECT_BY_ID, ROW_MAPPER, id, tenantId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Returns all teams for the given tournament, scoped to the current tenant, ordered by
     * team_number ascending.
     *
     * @param tournamentId the tournament to query
     * @return list of teams; never null
     */
    public List<Team> findByTournamentId(UUID tournamentId) {
        UUID tenantId = tenantContext.getTenantId();
        return jdbc.query(SELECT_BY_TOURNAMENT, ROW_MAPPER, tournamentId, tenantId);
    }

    /**
     * Deletes the team with the given id, scoped to the current tenant.
     *
     * @param id the team UUID
     */
    public void deleteById(UUID id) {
        UUID tenantId = tenantContext.getTenantId();
        jdbc.update(DELETE_BY_ID, id, tenantId);
    }

    /**
     * Returns the next available team number for the given tournament (max + 1, or 1 if empty).
     *
     * @param tournamentId the tournament to query
     * @return the next team number (≥ 1)
     */
    public int nextTeamNumber(UUID tournamentId) {
        return crudRepository.findMaxTeamNumberByTournamentId(tournamentId) + 1;
    }

    /**
     * Returns whether a team with the given team_number already exists in the tournament, excluding
     * a specific team (for update uniqueness checks).
     *
     * @param tournamentId the tournament scope
     * @param teamNumber the team number to check
     * @param excludeId UUID of the team to exclude (pass a zero UUID for new-team checks)
     * @return true if another team in the tournament already has this number
     */
    public boolean teamNumberExists(UUID tournamentId, int teamNumber, UUID excludeId) {
        return crudRepository.countByTournamentIdAndTeamNumberExcluding(
                        tournamentId, teamNumber, excludeId)
                > 0;
    }

    /**
     * Returns whether the given team has any {@code TeamAvatar} references (for delete guard).
     *
     * @param teamId the team UUID
     * @return true if at least one TeamAvatar references this team
     */
    public boolean hasTeamAvatars(UUID teamId) {
        return crudRepository.countAvatarsByTeamId(teamId) > 0;
    }

    // -------------------------------------------------------------------------
    // Row mapper
    // -------------------------------------------------------------------------

    private static final RowMapper<Team> ROW_MAPPER = (rs, rowNum) -> mapRow(rs);

    @SuppressWarnings("try") // ResultSet is not AutoCloseable; suppress spurious try-resource lint
    private static Team mapRow(ResultSet rs) throws SQLException {
        Team t = new Team();
        t.setId(rs.getObject("id", UUID.class));
        t.setTenantId(rs.getObject("tenant_id", UUID.class));
        t.setTournamentId(rs.getObject("tournament_id", UUID.class));
        t.setTeamNumber(rs.getInt("team_number"));
        t.setDescription(rs.getString("description"));
        t.setParticipate(rs.getBoolean("participate"));
        t.setRefereeAssignment(rs.getBoolean("referee_assignment"));
        t.setWithoutAssessment(rs.getBoolean("without_assessment"));
        t.setCreatedAt(
                rs.getObject("created_at") != null
                        ? rs.getTimestamp("created_at").toLocalDateTime()
                        : null);
        return t;
    }
}
