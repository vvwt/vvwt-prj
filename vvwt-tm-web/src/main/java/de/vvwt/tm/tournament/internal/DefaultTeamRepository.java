package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tournament.Team;
import de.vvwt.tm.tournament.TeamRepository;
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
 * Default implementation of {@link TeamRepository} (DEC-35, E31S01).
 *
 * <p>Uses plain {@link JdbcTemplate}. Tenant scoping enforced via active {@link TenantContext}.
 *
 * <p>Bean qualifier {@code "tmTeamRepository"} preserves injection compatibility with call sites
 * established in E21S04.
 *
 * @see TeamRepository
 * @see <a href="DEC-35">DEC-35 — package layout: impl in internal</a>
 * @see <a href="E21S04">E21S04 — Team aggregate reconstruction (inventory line 307)</a>
 * @see <a href="E31S01">E31S01 — interface extraction</a>
 */
@Repository("tmTeamRepository")
public class DefaultTeamRepository implements TeamRepository {

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;

    private static final String INSERT_SQL =
            "INSERT INTO team (id, tenant_id, tournament_id, team_number, description,"
                    + " participate, referee_assignment, without_assessment, created_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_SQL =
            "UPDATE team SET team_number=?, description=?, participate=?,"
                    + " referee_assignment=?, without_assessment=?"
                    + " WHERE id=? AND tenant_id=?";

    private static final String SELECT_BY_ID = "SELECT * FROM team WHERE id=? AND tenant_id=?";

    private static final String SELECT_BY_TOURNAMENT =
            "SELECT * FROM team WHERE tournament_id=? AND tenant_id=? ORDER BY team_number ASC";

    private static final String DELETE_BY_ID = "DELETE FROM team WHERE id=? AND tenant_id=?";

    private static final String EXISTS_BY_ID =
            "SELECT COUNT(*) FROM team WHERE id=? AND tenant_id=?";

    private static final String MAX_TEAM_NUMBER =
            "SELECT COALESCE(MAX(team_number), 0) FROM team WHERE tournament_id=?";

    private static final String COUNT_TEAM_NUMBER_EXCLUDING =
            "SELECT COUNT(*) FROM team WHERE tournament_id=? AND team_number=? AND id<>?";

    private static final String COUNT_AVATARS_BY_TEAM =
            "SELECT COUNT(*) FROM team_avatar WHERE team_id=?";

    public DefaultTeamRepository(JdbcTemplate jdbc, TenantContext tenantContext) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
    }

    /** {@inheritDoc} */
    @Override
    public Team save(Team team) {
        UUID currentTenantId = tenantContext.current();
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

    /** {@inheritDoc} */
    @Override
    public Optional<Team> findById(UUID id) {
        UUID tenantId = tenantContext.current();
        List<Team> results = jdbc.query(SELECT_BY_ID, ROW_MAPPER, id, tenantId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /** {@inheritDoc} */
    @Override
    public List<Team> findByTournamentId(UUID tournamentId) {
        UUID tenantId = tenantContext.current();
        return jdbc.query(SELECT_BY_TOURNAMENT, ROW_MAPPER, tournamentId, tenantId);
    }

    /** {@inheritDoc} */
    @Override
    public void deleteById(UUID id) {
        UUID tenantId = tenantContext.current();
        jdbc.update(DELETE_BY_ID, id, tenantId);
    }

    /** {@inheritDoc} */
    @Override
    public int nextTeamNumber(UUID tournamentId) {
        Integer max = jdbc.queryForObject(MAX_TEAM_NUMBER, Integer.class, tournamentId);
        return (max != null ? max : 0) + 1;
    }

    /** {@inheritDoc} */
    @Override
    public boolean teamNumberExists(UUID tournamentId, int teamNumber, UUID excludeId) {
        Integer count =
                jdbc.queryForObject(
                        COUNT_TEAM_NUMBER_EXCLUDING,
                        Integer.class,
                        tournamentId,
                        teamNumber,
                        excludeId);
        return count != null && count > 0;
    }

    /** {@inheritDoc} */
    @Override
    public boolean hasTeamAvatars(UUID teamId) {
        Integer count = jdbc.queryForObject(COUNT_AVATARS_BY_TEAM, Integer.class, teamId);
        return count != null && count > 0;
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
