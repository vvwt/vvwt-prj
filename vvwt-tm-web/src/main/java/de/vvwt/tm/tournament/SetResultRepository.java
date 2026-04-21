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
 * Tenant-scoped repository for {@link SetResult} entities (DEC-21, DEC-22, DEC-26, E21S05).
 *
 * <p>Boundary-API per inventory line 301 — consumed by {@code CascadeRecomputeService} in the
 * {@code scoring} context. Elevated to boundary-API per Brief D-4 refinement.
 *
 * <p>{@link SetResult} has a composite primary key {@code (match_id, set_index)} with no Spring
 * Data {@code @Id} field. This repository uses {@link JdbcTemplate} directly.
 *
 * <p>Bean qualifier {@code "tmSetResultRepository"} avoids collision with legacy {@code
 * de.vvwt.tm.domain.repo.SetResultRepository}.
 *
 * @see SetResult
 * @see SetState
 * @see <a href="DEC-21">DEC-21 — boundary-API placement</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-26">DEC-26 — DAO test governance</a>
 * @see <a href="E21S05">E21S05 — inventory line 301</a>
 */
@Repository("tmSetResultRepository")
public class SetResultRepository {

    private static final String INSERT_SQL =
            "INSERT INTO set_result (match_id, set_index, tenant_id, phase_id, team1_points,"
                    + " team2_points, set_state, change_time, created_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";

    private static final String UPDATE_SQL =
            "UPDATE set_result SET team1_points=?, team2_points=?, set_state=?,"
                    + " change_time=CURRENT_TIMESTAMP"
                    + " WHERE match_id=? AND set_index=? AND tenant_id=?";

    private static final String SELECT_BY_MATCH =
            "SELECT * FROM set_result WHERE match_id=? AND tenant_id=?";

    private static final String SELECT_BY_PK =
            "SELECT * FROM set_result WHERE match_id=? AND set_index=? AND tenant_id=?";

    private static final String DELETE_BY_PK =
            "DELETE FROM set_result WHERE match_id=? AND set_index=? AND tenant_id=?";

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;

    public SetResultRepository(JdbcTemplate jdbc, TenantContext tenantContext) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
    }

    /**
     * Inserts a new {@link SetResult} row.
     *
     * @param setResult the set result to insert (tenantId must match or be null for auto-fill)
     * @throws IllegalStateException if no tenant context is active
     * @throws IllegalArgumentException if the entity's tenantId does not match the active context
     */
    public void insert(SetResult setResult) {
        UUID activeTenant = tenantContext.current(); // guard fires here
        validateTenant(setResult.getTenantId(), activeTenant, setResult);
        jdbc.update(
                INSERT_SQL,
                setResult.getMatchId(),
                setResult.getSetIndex(),
                activeTenant,
                setResult.getPhaseId(),
                setResult.getTeam1Points(),
                setResult.getTeam2Points(),
                setResult.getSetStateCode());
    }

    /**
     * Updates an existing {@link SetResult} row by composite PK {@code (match_id, set_index)}.
     *
     * @param setResult the updated set result
     * @throws IllegalStateException if no tenant context is active
     */
    public void update(SetResult setResult) {
        UUID activeTenant = tenantContext.current(); // guard fires here
        validateTenant(setResult.getTenantId(), activeTenant, setResult);
        jdbc.update(
                UPDATE_SQL,
                setResult.getTeam1Points(),
                setResult.getTeam2Points(),
                setResult.getSetStateCode(),
                setResult.getMatchId(),
                setResult.getSetIndex(),
                activeTenant);
    }

    /**
     * Returns all set results for a given match, scoped to the active tenant.
     *
     * @param matchId the match whose set results to retrieve
     * @return list of set results ordered by {@code set_index}; never {@code null}
     * @throws IllegalStateException if no tenant context is active
     */
    public List<SetResult> findByMatchId(UUID matchId) {
        UUID activeTenant = tenantContext.current(); // guard fires here
        return jdbc.query(SELECT_BY_MATCH, ROW_MAPPER, matchId, activeTenant);
    }

    /**
     * Finds a set result by composite PK {@code (match_id, set_index)}, scoped to the active
     * tenant.
     *
     * @param matchId the match FK
     * @param setIndex the 0-based set index
     * @return the set result, or {@link Optional#empty()} if not found
     * @throws IllegalStateException if no tenant context is active
     */
    public Optional<SetResult> findByMatchIdAndSetIndex(UUID matchId, int setIndex) {
        UUID activeTenant = tenantContext.current(); // guard fires here
        List<SetResult> results =
                jdbc.query(SELECT_BY_PK, ROW_MAPPER, matchId, setIndex, activeTenant);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Deletes a set result by composite PK, scoped to the active tenant.
     *
     * @param matchId the match FK
     * @param setIndex the 0-based set index
     * @throws IllegalStateException if no tenant context is active
     */
    public void deleteByMatchIdAndSetIndex(UUID matchId, int setIndex) {
        UUID activeTenant = tenantContext.current(); // guard fires here
        jdbc.update(DELETE_BY_PK, matchId, setIndex, activeTenant);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void validateTenant(UUID entityTenant, UUID activeTenant, SetResult entity) {
        if (entityTenant != null && !activeTenant.equals(entityTenant)) {
            throw new IllegalArgumentException(
                    "Tenant spoof rejected: SetResult carries tenantId="
                            + entityTenant
                            + " but the active TenantContext is tenantId="
                            + activeTenant
                            + ". matchId="
                            + entity.getMatchId()
                            + ", setIndex="
                            + entity.getSetIndex());
        }
    }

    // -------------------------------------------------------------------------
    // Row mapper
    // -------------------------------------------------------------------------

    private static final RowMapper<SetResult> ROW_MAPPER = SetResultRepository::mapRow;

    private static SetResult mapRow(ResultSet rs, int rowNum) throws SQLException {
        SetResult sr = new SetResult();
        sr.setMatchId(rs.getObject("match_id", UUID.class));
        sr.setSetIndex(rs.getInt("set_index"));
        sr.setTenantId(rs.getObject("tenant_id", UUID.class));
        sr.setPhaseId(rs.getObject("phase_id", UUID.class));
        sr.setTeam1Points(rs.getInt("team1_points"));
        sr.setTeam2Points(rs.getInt("team2_points"));
        sr.setSetStateCode(rs.getInt("set_state"));
        sr.setChangeTime(rs.getObject("change_time", LocalDateTime.class));
        sr.setCreatedAt(rs.getObject("created_at", LocalDateTime.class));
        return sr;
    }
}
