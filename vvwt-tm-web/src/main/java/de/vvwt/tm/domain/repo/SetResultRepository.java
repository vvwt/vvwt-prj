package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.SetResult;
import de.vvwt.tm.domain.SetState;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped repository for {@link SetResult} entities.
 *
 * <p>{@link SetResult} has a composite primary key {@code (match_id, set_index)} with no
 * Spring Data {@code @Id} field (as noted in the E03S03 impl-report). Therefore this repository
 * does NOT extend {@link TenantScopedRepository} — it uses {@link NamedParameterJdbcTemplate}
 * directly and applies the tenant guard manually.
 *
 * <p>All methods call {@link TenantContext#getTenantId()} before any SQL, satisfying the AC6
 * runtime guard requirement. The AC5 append-only constraint does not apply here — see
 * {@link AuditLogRepository}.
 *
 * @see TenantContext
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E03S05.story.md">Story E03S05</a>
 */
@Repository
public class SetResultRepository {

    private static final String INSERT_SQL =
            "INSERT INTO set_result (match_id, set_index, tenant_id, phase_id, "
            + "team1_points, team2_points, set_state, change_time, created_at) "
            + "VALUES (:matchId, :setIndex, :tenantId, :phaseId, "
            + ":team1Points, :team2Points, :setState, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";

    private static final String UPDATE_SQL =
            "UPDATE set_result SET team1_points = :team1Points, team2_points = :team2Points, "
            + "set_state = :setState, change_time = CURRENT_TIMESTAMP "
            + "WHERE match_id = :matchId AND set_index = :setIndex AND tenant_id = :tenantId";

    private static final String SELECT_BY_MATCH_SQL =
            "SELECT * FROM set_result WHERE match_id = :matchId AND tenant_id = :tenantId";

    private static final String SELECT_BY_PK_SQL =
            "SELECT * FROM set_result WHERE match_id = :matchId AND set_index = :setIndex "
            + "AND tenant_id = :tenantId";

    private static final String DELETE_SQL =
            "DELETE FROM set_result WHERE match_id = :matchId AND set_index = :setIndex "
            + "AND tenant_id = :tenantId";

    private final NamedParameterJdbcTemplate jdbc;
    private final TenantContext tenantContext;

    public SetResultRepository(NamedParameterJdbcTemplate namedParameterJdbcTemplate,
                                TenantContext tenantContext) {
        this.jdbc = namedParameterJdbcTemplate;
        this.tenantContext = tenantContext;
    }

    /**
     * Inserts a new {@link SetResult} row.
     *
     * @param setResult the set result to insert (tenantId must match active context)
     * @throws IllegalStateException    if no tenant context is active
     * @throws IllegalArgumentException if the entity's tenantId does not match active context
     */
    public void insert(SetResult setResult) {
        UUID activeTenant = tenantContext.getTenantId();  // guard fires here — before SQL
        validateTenant(setResult.getTenantId(), activeTenant, setResult);
        MapSqlParameterSource params = buildParams(setResult, activeTenant);
        jdbc.update(INSERT_SQL, params);
    }

    /**
     * Updates an existing {@link SetResult} row by composite PK {@code (match_id, set_index)}.
     *
     * @param setResult the updated set result (tenantId must match active context)
     * @throws IllegalStateException    if no tenant context is active
     * @throws IllegalArgumentException if the entity's tenantId does not match active context
     */
    public void update(SetResult setResult) {
        UUID activeTenant = tenantContext.getTenantId();  // guard fires here — before SQL
        validateTenant(setResult.getTenantId(), activeTenant, setResult);
        MapSqlParameterSource params = buildParams(setResult, activeTenant);
        jdbc.update(UPDATE_SQL, params);
    }

    /**
     * Returns all set results for a given match, scoped to the active tenant.
     *
     * <p>Used by the cascade service (E03S11) to enumerate sets for a match.
     *
     * @param matchId the match whose set results to retrieve
     * @return list of set results ordered by {@code set_index}; never {@code null}
     * @throws IllegalStateException if no tenant context is active
     */
    public List<SetResult> findByMatchId(UUID matchId) {
        UUID activeTenant = tenantContext.getTenantId();  // guard fires here — before SQL
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("matchId", matchId)
                .addValue("tenantId", activeTenant);
        return jdbc.query(SELECT_BY_MATCH_SQL, params, SET_RESULT_ROW_MAPPER);
    }

    /**
     * Finds a set result by composite PK {@code (match_id, set_index)}, scoped to active tenant.
     *
     * @param matchId  the match FK
     * @param setIndex the 0-based set index
     * @return the set result, or {@link Optional#empty()} if not found
     * @throws IllegalStateException if no tenant context is active
     */
    public Optional<SetResult> findByMatchIdAndSetIndex(UUID matchId, int setIndex) {
        UUID activeTenant = tenantContext.getTenantId();  // guard fires here — before SQL
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("matchId", matchId)
                .addValue("setIndex", setIndex)
                .addValue("tenantId", activeTenant);
        List<SetResult> results = jdbc.query(SELECT_BY_PK_SQL, params, SET_RESULT_ROW_MAPPER);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Deletes a set result by composite PK, scoped to active tenant.
     *
     * @param matchId  the match FK
     * @param setIndex the 0-based set index
     * @throws IllegalStateException if no tenant context is active
     */
    public void deleteByMatchIdAndSetIndex(UUID matchId, int setIndex) {
        UUID activeTenant = tenantContext.getTenantId();  // guard fires here — before SQL
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("matchId", matchId)
                .addValue("setIndex", setIndex)
                .addValue("tenantId", activeTenant);
        jdbc.update(DELETE_SQL, params);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void validateTenant(UUID entityTenant, UUID activeTenant, SetResult entity) {
        if (entityTenant != null && !activeTenant.equals(entityTenant)) {
            throw new IllegalArgumentException(
                    "Tenant spoof rejected: SetResult carries tenantId=" + entityTenant
                    + " but the active TenantContext is tenantId=" + activeTenant
                    + ". matchId=" + entity.getMatchId() + ", setIndex=" + entity.getSetIndex());
        }
    }

    private MapSqlParameterSource buildParams(SetResult sr, UUID activeTenant) {
        return new MapSqlParameterSource()
                .addValue("matchId", sr.getMatchId())
                .addValue("setIndex", sr.getSetIndex())
                .addValue("tenantId", activeTenant)
                .addValue("phaseId", sr.getPhaseId())
                .addValue("team1Points", sr.getTeam1Points())
                .addValue("team2Points", sr.getTeam2Points())
                .addValue("setState", sr.getSetStateCode());
    }

    private static final RowMapper<SetResult> SET_RESULT_ROW_MAPPER = new RowMapper<>() {
        @Override
        public SetResult mapRow(ResultSet rs, int rowNum) throws SQLException {
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
    };
}
