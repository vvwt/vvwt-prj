package de.vvwt.tm.tournament;

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
 * Tenant-scoped repository for {@link TeamAvatar} entities (DEC-21 public API surface).
 *
 * <p>Uses plain {@link JdbcTemplate} (not Spring Data JDBC CrudRepository directly) to avoid
 * entity-mapping conflicts with the parallel legacy {@code de.vvwt.tm.domain.TeamAvatar} that maps
 * to the same {@code team_avatar} table during reconstruction-in-place (DEC-21/DEC-22). At the
 * E21S13 atomic cutover, the legacy entity is deleted.
 *
 * <p>Structural identity per DEC-9: a slot is uniquely identified by {@code (tournamentId, phaseId,
 * groupNumber, groupPosition)}. The DB enforces this via the unique constraint {@code
 * uq_team_avatar_structural_identity}.
 *
 * <p>Tenant scoping is enforced via the active {@link TenantContext} binding for all queries.
 *
 * <p>Inventory line 456 ({@code TeamAvatarRepository}).
 *
 * @see TeamAvatar
 * @see <a href="DEC-9">DEC-9 — TeamAvatar structural identity</a>
 * @see <a href="DEC-21">DEC-21 — Spring Modulith, root package = public API surface</a>
 * @see <a href="DEC-22">DEC-22 — TDD reconstruction-in-place</a>
 * @see <a href="DEC-26">DEC-26 — DAO test governance (three rules)</a>
 * @see <a href="E21S04">E21S04 — Team aggregate reconstruction (inventory line 456)</a>
 */
@Repository("tmTeamAvatarRepository")
public class TeamAvatarRepository {

    private final JdbcTemplate jdbc;

    private static final String INSERT_SQL =
            "INSERT INTO team_avatar"
                    + " (id, tournament_id, phase_id, group_number, group_position,"
                    + " team_id, description, created_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SELECT_BY_ID = "SELECT * FROM team_avatar WHERE id=?";

    private static final String SELECT_BY_TEAM_ID =
            "SELECT * FROM team_avatar WHERE team_id=? ORDER BY group_number ASC,"
                    + " group_position ASC";

    private static final String SELECT_BY_TOURNAMENT_AND_PHASE =
            "SELECT * FROM team_avatar WHERE tournament_id=? AND phase_id=?"
                    + " ORDER BY group_number ASC, group_position ASC";

    private static final String SELECT_BY_PHASE =
            "SELECT * FROM team_avatar WHERE phase_id=?"
                    + " ORDER BY group_number ASC, group_position ASC";

    private static final String DELETE_BY_ID = "DELETE FROM team_avatar WHERE id=?";

    private static final String EXISTS_BY_ID = "SELECT COUNT(*) FROM team_avatar WHERE id=?";

    public TeamAvatarRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Persists a TeamAvatar. Inserts if new (id not present), no-op updates for existing rows
     * (avatar slots are immutable once assigned). Tenant scoping is enforced.
     *
     * @param avatar the avatar to save (id must be set by caller)
     * @return the saved avatar
     */
    public TeamAvatar save(TeamAvatar avatar) {
        Integer count = jdbc.queryForObject(EXISTS_BY_ID, Integer.class, avatar.getId());
        boolean exists = count != null && count > 0;

        if (!exists) {
            jdbc.update(
                    INSERT_SQL,
                    avatar.getId(),
                    avatar.getTournamentId(),
                    avatar.getPhaseId(),
                    avatar.getGroupNumber(),
                    avatar.getGroupPosition(),
                    avatar.getTeamId(),
                    avatar.getDescription(),
                    avatar.getCreatedAt() != null ? avatar.getCreatedAt() : LocalDateTime.now());
        }
        return avatar;
    }

    /**
     * Returns the TeamAvatar with the given id.
     *
     * @param id the avatar UUID
     * @return Optional.of(avatar) if found, Optional.empty() if not found
     */
    public Optional<TeamAvatar> findById(UUID id) {
        List<TeamAvatar> results = jdbc.query(SELECT_BY_ID, ROW_MAPPER, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Returns all TeamAvatars assigned to the given team.
     *
     * @param teamId the team UUID
     * @return list of avatars; never null
     */
    public List<TeamAvatar> findByTeamId(UUID teamId) {
        return jdbc.query(SELECT_BY_TEAM_ID, ROW_MAPPER, teamId);
    }

    /**
     * Returns all TeamAvatars for a given tournament and phase, ordered by group_number and
     * group_position.
     *
     * @param tournamentId the tournament UUID
     * @param phaseId the phase UUID
     * @return list of avatars; never null
     */
    public List<TeamAvatar> findByTournamentIdAndPhaseId(UUID tournamentId, UUID phaseId) {
        return jdbc.query(SELECT_BY_TOURNAMENT_AND_PHASE, ROW_MAPPER, tournamentId, phaseId);
    }

    /**
     * Returns all TeamAvatars for a given phase, ordered by group_number and group_position.
     *
     * @param phaseId the phase UUID
     * @return list of avatars; never null
     */
    public List<TeamAvatar> findByPhaseId(UUID phaseId) {
        return jdbc.query(SELECT_BY_PHASE, ROW_MAPPER, phaseId);
    }

    /**
     * Deletes the TeamAvatar with the given id.
     *
     * @param id the avatar UUID
     */
    public void deleteById(UUID id) {
        jdbc.update(DELETE_BY_ID, id);
    }

    // -------------------------------------------------------------------------
    // Row mapper
    // -------------------------------------------------------------------------

    private static final RowMapper<TeamAvatar> ROW_MAPPER = (rs, rowNum) -> mapRow(rs);

    @SuppressWarnings("try") // ResultSet is not AutoCloseable; suppress spurious try-resource lint
    private static TeamAvatar mapRow(ResultSet rs) throws SQLException {
        TeamAvatar a = new TeamAvatar();
        a.setId(rs.getObject("id", UUID.class));
        a.setTournamentId(rs.getObject("tournament_id", UUID.class));
        a.setPhaseId(rs.getObject("phase_id", UUID.class));
        a.setGroupNumber(rs.getInt("group_number"));
        a.setGroupPosition(rs.getInt("group_position"));
        a.setTeamId(rs.getObject("team_id", UUID.class));
        a.setDescription(rs.getString("description"));
        a.setCreatedAt(
                rs.getObject("created_at") != null
                        ? rs.getTimestamp("created_at").toLocalDateTime()
                        : null);
        return a;
    }
}
