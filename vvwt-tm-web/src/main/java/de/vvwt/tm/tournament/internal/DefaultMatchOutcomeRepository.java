package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.MatchOutcome;
import de.vvwt.tm.tournament.MatchOutcomeRepository;
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
 * Default implementation of {@link MatchOutcomeRepository}.
 *
 * <p>Tenant-scoped repository for {@link MatchOutcome} entities (DEC-21, DEC-22, DEC-26, E21S05).
 *
 * <p>Boundary-API per inventory line 293. Uses plain {@link JdbcTemplate} to avoid entity-mapping
 * conflicts during the reconstruction-in-place phase (DEC-21/DEC-22). Bean qualifier {@code
 * "tmMatchOutcomeRepository"} avoids collision with legacy {@code
 * de.vvwt.tm.domain.repo.MatchOutcomeRepository}.
 *
 * @see MatchOutcome
 * @since E57S01 (DEC-58/DEC-72 interface extraction: renamed from MatchOutcomeRepository, moved to
 *     tournament.internal, implements {@link MatchOutcomeRepository})
 */
@Repository("tmMatchOutcomeRepository")
class DefaultMatchOutcomeRepository implements MatchOutcomeRepository {

    private final JdbcTemplate jdbc;

    private static final String INSERT_SQL =
            "INSERT INTO match_outcome (match_id, team1_sets_won, team1_balls_won,"
                    + " team2_sets_won, team2_balls_won, set_count, computed_state, updated_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_SQL =
            "UPDATE match_outcome SET team1_sets_won=?, team1_balls_won=?, team2_sets_won=?,"
                    + " team2_balls_won=?, set_count=?, computed_state=?, updated_at=?"
                    + " WHERE match_id=?";

    private static final String SELECT_BY_ID = "SELECT * FROM match_outcome WHERE match_id=?";

    private static final String EXISTS_BY_ID =
            "SELECT COUNT(*) FROM match_outcome WHERE match_id=?";

    private static final String DELETE_BY_MATCH_ID = "DELETE FROM match_outcome WHERE match_id=?";

    DefaultMatchOutcomeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public MatchOutcome save(MatchOutcome matchOutcome) {
        Integer count = jdbc.queryForObject(EXISTS_BY_ID, Integer.class, matchOutcome.getMatchId());
        boolean exists = count != null && count > 0;

        LocalDateTime now = LocalDateTime.now();
        if (exists) {
            jdbc.update(
                    UPDATE_SQL,
                    matchOutcome.getTeam1SetsWon(),
                    matchOutcome.getTeam1BallsWon(),
                    matchOutcome.getTeam2SetsWon(),
                    matchOutcome.getTeam2BallsWon(),
                    matchOutcome.getSetCount(),
                    matchOutcome.getComputedState(),
                    matchOutcome.getUpdatedAt() != null ? matchOutcome.getUpdatedAt() : now,
                    matchOutcome.getMatchId());
        } else {
            jdbc.update(
                    INSERT_SQL,
                    matchOutcome.getMatchId(),
                    matchOutcome.getTeam1SetsWon(),
                    matchOutcome.getTeam1BallsWon(),
                    matchOutcome.getTeam2SetsWon(),
                    matchOutcome.getTeam2BallsWon(),
                    matchOutcome.getSetCount(),
                    matchOutcome.getComputedState(),
                    matchOutcome.getUpdatedAt() != null ? matchOutcome.getUpdatedAt() : now);
        }
        return matchOutcome;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<MatchOutcome> findById(UUID matchId) {
        List<MatchOutcome> results = jdbc.query(SELECT_BY_ID, ROW_MAPPER, matchId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /** {@inheritDoc} */
    @Override
    public void deleteByMatchId(UUID matchId) {
        jdbc.update(DELETE_BY_MATCH_ID, matchId);
    }

    private static final RowMapper<MatchOutcome> ROW_MAPPER = DefaultMatchOutcomeRepository::mapRow;

    private static MatchOutcome mapRow(ResultSet rs, int rowNum) throws SQLException {
        MatchOutcome mo = new MatchOutcome();
        mo.setMatchId(rs.getObject("match_id", UUID.class));
        mo.setTeam1SetsWon(rs.getInt("team1_sets_won"));
        mo.setTeam1BallsWon(rs.getInt("team1_balls_won"));
        mo.setTeam2SetsWon(rs.getInt("team2_sets_won"));
        mo.setTeam2BallsWon(rs.getInt("team2_balls_won"));
        mo.setSetCount(rs.getInt("set_count"));
        mo.setComputedState(rs.getInt("computed_state"));
        mo.setUpdatedAt(rs.getObject("updated_at", LocalDateTime.class));
        return mo;
    }
}
