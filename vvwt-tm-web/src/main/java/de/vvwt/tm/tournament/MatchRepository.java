package de.vvwt.tm.tournament;

import de.vvwt.tm.domain.repo.TenantContext;
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
 * Tenant-scoped repository for {@link Match} entities (DEC-21, DEC-22, DEC-26, E21S05).
 *
 * <p>Boundary-API of the {@code tournament} Modulith context per inventory line 294 (12 importers
 * across slotopt, scoring, display, print, and timer contexts).
 *
 * <p>Uses plain {@link JdbcTemplate} (not Spring Data JDBC CrudRepository) to avoid entity-mapping
 * conflicts with the parallel legacy {@code de.vvwt.tm.domain.Match} entity that maps to the same
 * {@code match} table during the reconstruction-in-place phase (DEC-21/DEC-22). At the E21S13
 * atomic cutover, the legacy entity is deleted.
 *
 * <p>Tenant scoping is enforced via the active {@link TenantContext} binding for all queries. The
 * tenant guard fires before any SQL is executed (AC-TENANT-GUARD pattern from
 * TenantScopedRepository).
 *
 * <p>Bean qualifier {@code "tmMatchRepository"} avoids Spring bean-name collision with the legacy
 * {@code de.vvwt.tm.domain.repo.MatchRepository} during the parallel-development phase.
 *
 * @see Match
 * @see MatchState
 * @see <a href="DEC-21">DEC-21 — boundary-API placement</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-26">DEC-26 — DAO test governance</a>
 * @see <a href="E21S05">E21S05 — inventory lines 173, 294</a>
 */
@Repository("tmMatchRepository")
public class MatchRepository {

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;

    private static final String INSERT_SQL =
            "INSERT INTO match (id, tenant_id, tournament_id, phase_id,"
                    + " member_avatar_1_id, member_avatar_2_id, state, set_limit,"
                    + " lap_number, field_number, referee_team_id, referee_description,"
                    + " referee_preference_config, created_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_SQL =
            "UPDATE match SET tournament_id=?, phase_id=?, member_avatar_1_id=?,"
                    + " member_avatar_2_id=?, state=?, set_limit=?, lap_number=?, field_number=?,"
                    + " referee_team_id=?, referee_description=?, referee_preference_config=?"
                    + " WHERE id=? AND tenant_id=?";

    private static final String SELECT_BY_ID = "SELECT * FROM match WHERE id=? AND tenant_id=?";

    private static final String SELECT_ALL = "SELECT * FROM match WHERE tenant_id=?";

    private static final String SELECT_BY_PHASE =
            "SELECT * FROM match WHERE phase_id=? AND tenant_id=?";

    private static final String SELECT_BY_FIELD_LAP =
            "SELECT * FROM match WHERE field_number=? AND lap_number=? AND tenant_id=?";

    private static final String SELECT_TERMINAL_BY_PHASE_AND_AVATAR =
            "SELECT * FROM match WHERE phase_id=? AND tenant_id=?"
                    + " AND (member_avatar_1_id=? OR member_avatar_2_id=?)"
                    + " AND state IN (50, 51, 52)";

    private static final String DELETE_BY_PHASE =
            "DELETE FROM match WHERE phase_id=? AND tenant_id=?";

    private static final String DELETE_BY_ID = "DELETE FROM match WHERE id=? AND tenant_id=?";

    private static final String EXISTS_BY_ID =
            "SELECT COUNT(*) FROM match WHERE id=? AND tenant_id=?";

    public MatchRepository(JdbcTemplate jdbc, TenantContext tenantContext) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
    }

    /**
     * Persists a match. Inserts if new (no existing row with this id+tenantId), updates otherwise.
     * Tenant scoping is enforced — the entity's tenantId is set to the current tenant before
     * insert.
     *
     * @param match the match to save (id must be set by caller)
     * @return the saved match
     * @throws IllegalStateException if no tenant context is active
     */
    public Match save(Match match) {
        UUID currentTenantId = tenantContext.getTenantId(); // guard fires here
        match.setTenantId(currentTenantId);

        Integer count =
                jdbc.queryForObject(EXISTS_BY_ID, Integer.class, match.getId(), currentTenantId);
        boolean exists = count != null && count > 0;

        if (exists) {
            jdbc.update(
                    UPDATE_SQL,
                    match.getTournamentId(),
                    match.getPhaseId(),
                    match.getMemberAvatar1Id(),
                    match.getMemberAvatar2Id(),
                    match.getState(),
                    match.getSetLimit(),
                    match.getLapNumber(),
                    match.getFieldNumber(),
                    match.getRefereeTeamId(),
                    match.getRefereeDescription(),
                    match.getRefereePreferenceConfig(),
                    match.getId(),
                    currentTenantId);
        } else {
            jdbc.update(
                    INSERT_SQL,
                    match.getId(),
                    currentTenantId,
                    match.getTournamentId(),
                    match.getPhaseId(),
                    match.getMemberAvatar1Id(),
                    match.getMemberAvatar2Id(),
                    match.getState(),
                    match.getSetLimit(),
                    match.getLapNumber(),
                    match.getFieldNumber(),
                    match.getRefereeTeamId(),
                    match.getRefereeDescription(),
                    match.getRefereePreferenceConfig(),
                    match.getCreatedAt() != null ? match.getCreatedAt() : LocalDateTime.now());
        }
        return match;
    }

    /**
     * Returns the match with the given id, scoped to the current tenant.
     *
     * @param id the match UUID
     * @return Optional.of(match) if found, Optional.empty() if not found or wrong tenant
     * @throws IllegalStateException if no tenant context is active
     */
    public Optional<Match> findById(UUID id) {
        UUID tenantId = tenantContext.getTenantId(); // guard fires here
        List<Match> results = jdbc.query(SELECT_BY_ID, ROW_MAPPER, id, tenantId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Returns all matches for the current tenant.
     *
     * @return list of all matches for the active tenant; never {@code null}
     * @throws IllegalStateException if no tenant context is active
     */
    public List<Match> findAll() {
        UUID tenantId = tenantContext.getTenantId(); // guard fires here
        return jdbc.query(SELECT_ALL, ROW_MAPPER, tenantId);
    }

    /**
     * Returns all matches in the given phase, scoped to the current tenant.
     *
     * @param phaseId the phase to query
     * @return list of matches in the given phase for the active tenant; never {@code null}
     * @throws IllegalStateException if no tenant context is active
     */
    public List<Match> findByPhaseId(UUID phaseId) {
        UUID tenantId = tenantContext.getTenantId(); // guard fires here
        return jdbc.query(SELECT_BY_PHASE, ROW_MAPPER, phaseId, tenantId);
    }

    /**
     * Returns all matches on the given field in the given lap, scoped to the current tenant.
     *
     * @param fieldNumber the court field number
     * @param lapNumber the lap (round) number
     * @return list of matches on the field in the lap; never {@code null}
     * @throws IllegalStateException if no tenant context is active
     */
    public List<Match> findByFieldNumberAndLapNumber(int fieldNumber, int lapNumber) {
        UUID tenantId = tenantContext.getTenantId(); // guard fires here
        return jdbc.query(SELECT_BY_FIELD_LAP, ROW_MAPPER, fieldNumber, lapNumber, tenantId);
    }

    /**
     * Returns all terminal matches for the given avatar in the given phase, scoped to the current
     * tenant.
     *
     * @param phaseId the phase to query
     * @param avatarId the team avatar to find matches for
     * @return list of terminal matches; never {@code null}
     * @throws IllegalStateException if no tenant context is active
     */
    public List<Match> findTerminalByPhaseIdAndAvatarId(UUID phaseId, UUID avatarId) {
        UUID tenantId = tenantContext.getTenantId(); // guard fires here
        return jdbc.query(
                SELECT_TERMINAL_BY_PHASE_AND_AVATAR,
                ROW_MAPPER,
                phaseId,
                tenantId,
                avatarId,
                avatarId);
    }

    /**
     * Deletes all matches for the given phase, scoped to the current tenant.
     *
     * @param phaseId the phase whose matches to delete
     * @throws IllegalStateException if no tenant context is active
     */
    public void deleteByPhaseId(UUID phaseId) {
        UUID tenantId = tenantContext.getTenantId(); // guard fires here
        jdbc.update(DELETE_BY_PHASE, phaseId, tenantId);
    }

    /**
     * Deletes the match with the given id, scoped to the current tenant.
     *
     * @param id the match UUID
     * @throws IllegalStateException if no tenant context is active
     */
    public void deleteById(UUID id) {
        UUID tenantId = tenantContext.getTenantId(); // guard fires here
        jdbc.update(DELETE_BY_ID, id, tenantId);
    }

    // -------------------------------------------------------------------------
    // Row mapper
    // -------------------------------------------------------------------------

    private static final RowMapper<Match> ROW_MAPPER = MatchRepository::mapRow;

    private static Match mapRow(ResultSet rs, int rowNum) throws SQLException {
        Match m = new Match();
        m.setId(rs.getObject("id", UUID.class));
        m.setTenantId(rs.getObject("tenant_id", UUID.class));
        m.setTournamentId(rs.getObject("tournament_id", UUID.class));
        m.setPhaseId(rs.getObject("phase_id", UUID.class));
        m.setMemberAvatar1Id(rs.getObject("member_avatar_1_id", UUID.class));
        m.setMemberAvatar2Id(rs.getObject("member_avatar_2_id", UUID.class));
        m.setState(rs.getInt("state"));
        m.setSetLimit(rs.getInt("set_limit"));
        int lapNumber = rs.getInt("lap_number");
        m.setLapNumber(rs.wasNull() ? null : lapNumber);
        int fieldNumber = rs.getInt("field_number");
        m.setFieldNumber(rs.wasNull() ? null : fieldNumber);
        m.setRefereeTeamId(rs.getObject("referee_team_id", UUID.class));
        m.setRefereeDescription(rs.getString("referee_description"));
        m.setRefereePreferenceConfig(rs.getString("referee_preference_config"));
        m.setCreatedAt(rs.getObject("created_at", LocalDateTime.class));
        return m;
    }
}
