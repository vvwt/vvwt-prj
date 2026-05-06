package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.Match;
import de.vvwt.tm.tournament.MatchRepository;
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
 * Default implementation of {@link MatchRepository} (DEC-35, E31S01).
 *
 * <p>Uses plain {@link JdbcTemplate}. Tenant scoping enforced via active {@link TenantContext}.
 *
 * <p>Bean qualifier {@code "tmMatchRepository"} preserves injection compatibility with call sites
 * established in E21S05.
 *
 * @see MatchRepository
 * @see <a href="DEC-35">DEC-35 — package layout: impl in internal</a>
 * @see <a href="E21S05">E21S05 — inventory lines 173, 294</a>
 * @see <a href="E31S01">E31S01 — interface extraction</a>
 */
@Repository("tmMatchRepository")
public class DefaultMatchRepository implements MatchRepository {

    private final JdbcTemplate jdbc;

    private static final String INSERT_SQL =
            "INSERT INTO match (id, tournament_id, phase_id,"
                    + " member_avatar_1_id, member_avatar_2_id, state, set_limit,"
                    + " lap_number, field_number, referee_team_id, referee_description,"
                    + " referee_preference_config, created_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_SQL =
            "UPDATE match SET tournament_id=?, phase_id=?, member_avatar_1_id=?,"
                    + " member_avatar_2_id=?, state=?, set_limit=?, lap_number=?, field_number=?,"
                    + " referee_team_id=?, referee_description=?, referee_preference_config=?"
                    + " WHERE id=?";

    private static final String SELECT_BY_ID = "SELECT * FROM match WHERE id=?";

    private static final String SELECT_ALL = "SELECT * FROM match";

    private static final String SELECT_BY_PHASE = "SELECT * FROM match WHERE phase_id=?";

    private static final String SELECT_BY_FIELD_LAP =
            "SELECT * FROM match WHERE field_number=? AND lap_number=?";

    private static final String SELECT_TERMINAL_BY_PHASE_AND_AVATAR =
            "SELECT * FROM match WHERE phase_id=?"
                    + " AND (member_avatar_1_id=? OR member_avatar_2_id=?)"
                    + " AND state IN (50, 51, 52)";

    private static final String DELETE_BY_PHASE = "DELETE FROM match WHERE phase_id=?";

    private static final String DELETE_BY_ID = "DELETE FROM match WHERE id=?";

    private static final String EXISTS_BY_ID = "SELECT COUNT(*) FROM match WHERE id=?";

    /**
     * Bulk-cancels all unfinished matches for a tournament (E48S04, AC-IMPL-MATCH-BULK-CANCEL).
     *
     * <p>SQL: {@code UPDATE match SET state = -10 WHERE tournament_id = ? AND state IN (0, 10, 30,
     * 35)}. Finished and already-cancelled matches are excluded — idempotent.
     */
    private static final String BULK_CANCEL_OPEN_MATCHES =
            "UPDATE match SET state = -10 WHERE tournament_id = ? AND state IN (0, 10, 30, 35)";

    public DefaultMatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public Match save(Match match) {
        Integer count = jdbc.queryForObject(EXISTS_BY_ID, Integer.class, match.getId());
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
                    match.getId());
        } else {
            jdbc.update(
                    INSERT_SQL,
                    match.getId(),
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

    /** {@inheritDoc} */
    @Override
    public Optional<Match> findById(UUID id) {
        List<Match> results = jdbc.query(SELECT_BY_ID, ROW_MAPPER, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /** {@inheritDoc} */
    @Override
    public List<Match> findAll() {
        return jdbc.query(SELECT_ALL, ROW_MAPPER);
    }

    /** {@inheritDoc} */
    @Override
    public List<Match> findByPhaseId(UUID phaseId) {
        return jdbc.query(SELECT_BY_PHASE, ROW_MAPPER, phaseId);
    }

    /** {@inheritDoc} */
    @Override
    public List<Match> findByFieldNumberAndLapNumber(int fieldNumber, int lapNumber) {
        return jdbc.query(SELECT_BY_FIELD_LAP, ROW_MAPPER, fieldNumber, lapNumber);
    }

    /** {@inheritDoc} */
    @Override
    public List<Match> findTerminalByPhaseIdAndAvatarId(UUID phaseId, UUID avatarId) {
        return jdbc.query(
                SELECT_TERMINAL_BY_PHASE_AND_AVATAR, ROW_MAPPER, phaseId, avatarId, avatarId);
    }

    /** {@inheritDoc} */
    @Override
    public void deleteByPhaseId(UUID phaseId) {
        jdbc.update(DELETE_BY_PHASE, phaseId);
    }

    /** {@inheritDoc} */
    @Override
    public void deleteById(UUID id) {
        jdbc.update(DELETE_BY_ID, id);
    }

    /** {@inheritDoc} */
    @Override
    public int cancelOpenMatchesByTournamentId(UUID tournamentId) {
        return jdbc.update(BULK_CANCEL_OPEN_MATCHES, tournamentId);
    }

    // -------------------------------------------------------------------------
    // Row mapper
    // -------------------------------------------------------------------------

    private static final RowMapper<Match> ROW_MAPPER = DefaultMatchRepository::mapRow;

    private static Match mapRow(ResultSet rs, int rowNum) throws SQLException {
        Match m = new Match();
        m.setId(rs.getObject("id", UUID.class));
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
