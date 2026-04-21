package de.vvwt.tm.tournament;

import de.vvwt.tm.tenant.TenantContext;
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
 * Tenant-scoped repository for {@link MatchOutcome} entities (DEC-21, DEC-22, DEC-26, E21S05).
 *
 * <p>Boundary-API per inventory line 293 — consumed by {@code CascadeRecomputeService} in the
 * {@code scoring} context. Elevated to boundary-API per Brief D-4 refinement.
 *
 * <p>Uses plain {@link JdbcTemplate} to avoid entity-mapping conflicts during the
 * reconstruction-in-place phase (DEC-21/DEC-22). Bean qualifier {@code "tmMatchOutcomeRepository"}
 * avoids collision with legacy {@code de.vvwt.tm.domain.repo.MatchOutcomeRepository}.
 *
 * @see MatchOutcome
 * @see <a href="DEC-21">DEC-21 — boundary-API placement</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-26">DEC-26 — DAO test governance</a>
 * @see <a href="E21S05">E21S05 — inventory line 293</a>
 */
@Repository("tmMatchOutcomeRepository")
public class MatchOutcomeRepository {

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;

    private static final String INSERT_SQL =
            "INSERT INTO match_outcome (match_id, tenant_id, team1_sets_won, team1_balls_won,"
                    + " team2_sets_won, team2_balls_won, set_count, computed_state, updated_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_SQL =
            "UPDATE match_outcome SET team1_sets_won=?, team1_balls_won=?, team2_sets_won=?,"
                    + " team2_balls_won=?, set_count=?, computed_state=?, updated_at=?"
                    + " WHERE match_id=? AND tenant_id=?";

    private static final String SELECT_BY_ID =
            "SELECT * FROM match_outcome WHERE match_id=? AND tenant_id=?";

    private static final String EXISTS_BY_ID =
            "SELECT COUNT(*) FROM match_outcome WHERE match_id=? AND tenant_id=?";

    private static final String DELETE_BY_MATCH_ID =
            "DELETE FROM match_outcome WHERE match_id=? AND tenant_id=?";

    public MatchOutcomeRepository(JdbcTemplate jdbc, TenantContext tenantContext) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
    }

    /**
     * Persists a match outcome. Inserts if new, updates otherwise.
     *
     * @param matchOutcome the outcome to save (matchId must be set by caller)
     * @return the saved match outcome
     * @throws IllegalStateException if no tenant context is active
     */
    public MatchOutcome save(MatchOutcome matchOutcome) {
        UUID currentTenantId = tenantContext.current(); // guard fires here
        matchOutcome.setTenantId(currentTenantId);

        Integer count =
                jdbc.queryForObject(
                        EXISTS_BY_ID, Integer.class, matchOutcome.getMatchId(), currentTenantId);
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
                    matchOutcome.getMatchId(),
                    currentTenantId);
        } else {
            jdbc.update(
                    INSERT_SQL,
                    matchOutcome.getMatchId(),
                    currentTenantId,
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

    /**
     * Returns the match outcome for the given match id, scoped to the current tenant.
     *
     * @param matchId the match UUID (same as match_outcome primary key)
     * @return Optional.of(outcome) if found, Optional.empty() if not found
     * @throws IllegalStateException if no tenant context is active
     */
    public Optional<MatchOutcome> findById(UUID matchId) {
        UUID tenantId = tenantContext.current(); // guard fires here
        List<MatchOutcome> results = jdbc.query(SELECT_BY_ID, ROW_MAPPER, matchId, tenantId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Deletes the match outcome for the given match id, scoped to the current tenant.
     *
     * @param matchId the match UUID
     * @throws IllegalStateException if no tenant context is active
     */
    public void deleteByMatchId(UUID matchId) {
        UUID tenantId = tenantContext.current(); // guard fires here
        jdbc.update(DELETE_BY_MATCH_ID, matchId, tenantId);
    }

    // -------------------------------------------------------------------------
    // Row mapper
    // -------------------------------------------------------------------------

    private static final RowMapper<MatchOutcome> ROW_MAPPER = MatchOutcomeRepository::mapRow;

    private static MatchOutcome mapRow(ResultSet rs, int rowNum) throws SQLException {
        MatchOutcome mo = new MatchOutcome();
        mo.setMatchId(rs.getObject("match_id", UUID.class));
        mo.setTenantId(rs.getObject("tenant_id", UUID.class));
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
