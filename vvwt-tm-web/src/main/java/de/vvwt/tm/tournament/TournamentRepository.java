package de.vvwt.tm.tournament;

import de.vvwt.tm.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Tenant-scoped repository for {@link Tournament} entities (DEC-21 public API surface).
 *
 * <p>Implements the boundary API for the {@code tournament} bounded context. With 15 importers
 * across downstream contexts ({@code scoring}, {@code certificate}, {@code print}, {@code display},
 * {@code timer}, {@code slotopt-integration}), this repository is the primary data-access surface
 * of the hub aggregate.
 *
 * <p>Uses plain {@link JdbcTemplate} (not Spring Data JDBC CrudRepository) to avoid entity-mapping
 * conflicts with the parallel legacy {@code de.vvwt.tm.domain.Tournament} entity that maps to the
 * same {@code tournament} table during the reconstruction-in-place phase (DEC-21/DEC-22). At the
 * E21S13 atomic cutover, the legacy entity is deleted and this repository can optionally be
 * migrated to Spring Data JDBC.
 *
 * <p>Tenant scoping is enforced via the active {@link TenantContext} binding for all queries.
 *
 * <p>Corresponds to inventory line 311: {@code de.vvwt.tm.domain.repo.TournamentRepository}.
 *
 * @see <a href="DEC-21">DEC-21 — Spring Modulith, root package = public API surface</a>
 * @see <a href="DEC-22">DEC-22 — TDD reconstruction-in-place</a>
 * @see <a href="DEC-26">DEC-26 — DAO test governance (three rules)</a>
 * @see <a href="E21S02">E21S02 — Tournament aggregate reconstruction (inventory line 311)</a>
 */
@Repository("tmTournamentRepository")
public class TournamentRepository {

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;

    private static final String INSERT_SQL =
            "INSERT INTO tournament (id, tenant_id, description, match_format,"
                    + " scoring_rule_id, set_validation_rule_id, match_generator_id,"
                    + " status, created_at, appointment, field_count, team_count,"
                    + " planned_start_time, draft_json)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_SQL =
            "UPDATE tournament SET description=?, match_format=?, scoring_rule_id=?,"
                    + " set_validation_rule_id=?, match_generator_id=?, status=?,"
                    + " appointment=?, field_count=?, team_count=?, planned_start_time=?,"
                    + " draft_json=? WHERE id=? AND tenant_id=?";

    private static final String SELECT_BY_ID =
            "SELECT * FROM tournament WHERE id=? AND tenant_id=?";

    private static final String SELECT_ALL = "SELECT * FROM tournament WHERE tenant_id=?";

    private static final String DELETE_BY_ID = "DELETE FROM tournament WHERE id=? AND tenant_id=?";

    private static final String EXISTS_BY_ID =
            "SELECT COUNT(*) FROM tournament WHERE id=? AND tenant_id=?";

    public TournamentRepository(JdbcTemplate jdbc, TenantContext tenantContext) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
    }

    /**
     * Persists a tournament. Inserts if new (no existing row with this id+tenantId), updates
     * otherwise. Tenant scoping is enforced — the entity's tenantId is set to the current tenant
     * before insert.
     *
     * @param tournament the tournament to save (id must be set by caller)
     * @return the saved tournament
     */
    public Tournament save(Tournament tournament) {
        UUID currentTenantId = tenantContext.current();
        tournament.setTenantId(currentTenantId);

        boolean exists =
                Boolean.TRUE.equals(
                        jdbc.queryForObject(
                                                EXISTS_BY_ID,
                                                Integer.class,
                                                tournament.getId(),
                                                currentTenantId)
                                        != null
                                && jdbc.queryForObject(
                                                EXISTS_BY_ID,
                                                Integer.class,
                                                tournament.getId(),
                                                currentTenantId)
                                        > 0);

        if (exists) {
            jdbc.update(
                    UPDATE_SQL,
                    tournament.getDescription(),
                    tournament.getMatchFormat(),
                    tournament.getScoringRuleId(),
                    tournament.getSetValidationRuleId(),
                    tournament.getMatchGeneratorId(),
                    tournament.getStatus(),
                    tournament.getAppointment(),
                    tournament.getFieldCount(),
                    tournament.getTeamCount(),
                    tournament.getPlannedStartTime(),
                    tournament.getDraftJson(),
                    tournament.getId(),
                    currentTenantId);
        } else {
            jdbc.update(
                    INSERT_SQL,
                    tournament.getId(),
                    currentTenantId,
                    tournament.getDescription(),
                    tournament.getMatchFormat(),
                    tournament.getScoringRuleId(),
                    tournament.getSetValidationRuleId(),
                    tournament.getMatchGeneratorId(),
                    tournament.getStatus(),
                    tournament.getCreatedAt() != null
                            ? tournament.getCreatedAt()
                            : LocalDateTime.now(),
                    tournament.getAppointment(),
                    tournament.getFieldCount(),
                    tournament.getTeamCount(),
                    tournament.getPlannedStartTime(),
                    tournament.getDraftJson());
        }
        return tournament;
    }

    /**
     * Returns the tournament with the given id, scoped to the current tenant.
     *
     * @param id the tournament UUID
     * @return Optional.of(tournament) if found, Optional.empty() if not found or wrong tenant
     */
    public Optional<Tournament> findById(UUID id) {
        UUID tenantId = tenantContext.current();
        List<Tournament> results = jdbc.query(SELECT_BY_ID, ROW_MAPPER, id, tenantId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Returns all tournaments for the current tenant.
     *
     * @return immutable list of tournaments; never null
     */
    public List<Tournament> findAll() {
        UUID tenantId = tenantContext.current();
        return jdbc.query(SELECT_ALL, ROW_MAPPER, tenantId);
    }

    /**
     * Deletes the tournament with the given id, scoped to the current tenant.
     *
     * @param id the tournament UUID
     */
    public void deleteById(UUID id) {
        UUID tenantId = tenantContext.current();
        jdbc.update(DELETE_BY_ID, id, tenantId);
    }

    // -------------------------------------------------------------------------
    // Row mapper
    // -------------------------------------------------------------------------

    private static final RowMapper<Tournament> ROW_MAPPER = (rs, rowNum) -> mapRow(rs);

    @SuppressWarnings("try") // ResultSet is not AutoCloseable; suppress spurious try-resource lint
    private static Tournament mapRow(ResultSet rs) throws SQLException {
        Tournament t = new Tournament();
        t.setId(rs.getObject("id", UUID.class));
        t.setTenantId(rs.getObject("tenant_id", UUID.class));
        t.setDescription(rs.getString("description"));
        t.setMatchFormat(rs.getString("match_format"));
        t.setScoringRuleId(rs.getString("scoring_rule_id"));
        t.setSetValidationRuleId(rs.getString("set_validation_rule_id"));
        t.setMatchGeneratorId(rs.getString("match_generator_id"));
        t.setStatus(rs.getString("status"));
        t.setCreatedAt(
                rs.getObject("created_at") != null
                        ? rs.getTimestamp("created_at").toLocalDateTime()
                        : null);
        t.setAppointment(
                rs.getObject("appointment") != null
                        ? rs.getTimestamp("appointment").toLocalDateTime()
                        : null);
        t.setFieldCount(rs.getInt("field_count"));
        t.setTeamCount(rs.getInt("team_count"));
        LocalTime pst =
                rs.getObject("planned_start_time") != null
                        ? rs.getTime("planned_start_time").toLocalTime()
                        : null;
        t.setPlannedStartTime(pst);
        t.setDraftJson(rs.getString("draft_json"));
        return t;
    }
}
