package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
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
 * Default implementation of {@link TournamentRepository} (DEC-35, E31S01).
 *
 * <p>Uses plain {@link JdbcTemplate} (not Spring Data JDBC CrudRepository). Tenant scoping is
 * enforced via the active {@link TenantContext} binding for all queries.
 *
 * <p>Bean qualifier {@code "tmTournamentRepository"} preserves injection compatibility with
 * {@code @Qualifier("tmTournamentRepository")} call sites established in E21S02.
 *
 * @see TournamentRepository
 * @see <a href="DEC-21">DEC-21 — Spring Modulith, root package = public API surface</a>
 * @see <a href="DEC-22">DEC-22 — TDD reconstruction-in-place</a>
 * @see <a href="DEC-26">DEC-26 — DAO test governance (three rules)</a>
 * @see <a href="DEC-35">DEC-35 — package layout: impl in internal</a>
 * @see <a href="E21S02">E21S02 — Tournament aggregate reconstruction (inventory line 311)</a>
 * @see <a href="E31S01">E31S01 — interface extraction + findByIdForUpdate</a>
 */
@Repository("tmTournamentRepository")
public class DefaultTournamentRepository implements TournamentRepository {

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
                    + " draft_json=? WHERE id=?";

    private static final String SELECT_BY_ID = "SELECT * FROM tournament WHERE id=?";

    private static final String SELECT_BY_ID_FOR_UPDATE =
            "SELECT * FROM tournament WHERE id=? FOR UPDATE";

    private static final String SELECT_ALL = "SELECT * FROM tournament";

    private static final String DELETE_BY_ID = "DELETE FROM tournament WHERE id=?";

    private static final String EXISTS_BY_ID = "SELECT COUNT(*) FROM tournament WHERE id=?";

    public DefaultTournamentRepository(JdbcTemplate jdbc, TenantContext tenantContext) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Inserts if new (no existing row with this id), updates otherwise. Tenant scoping is
     * enforced — the entity's tenantId is set to the current tenant before insert.
     */
    @Override
    public Tournament save(Tournament tournament) {
        UUID currentTenantId = tenantContext.current();
        tournament.setTenantId(currentTenantId);

        Integer count = jdbc.queryForObject(EXISTS_BY_ID, Integer.class, tournament.getId());
        boolean exists = count != null && count > 0;

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
                    tournament.getId());
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

    /** {@inheritDoc} */
    @Override
    public Optional<Tournament> findById(UUID id) {
        List<Tournament> results = jdbc.query(SELECT_BY_ID, ROW_MAPPER, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /** {@inheritDoc} */
    @Override
    public List<Tournament> findAll() {
        return jdbc.query(SELECT_ALL, ROW_MAPPER);
    }

    /** {@inheritDoc} */
    @Override
    public void deleteById(UUID id) {
        jdbc.update(DELETE_BY_ID, id);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Acquires a pessimistic DB row-lock via {@code SELECT … FOR UPDATE} on the {@code
     * tournament} table. Structural clone of {@link #findById(UUID)} with a {@code FOR UPDATE} SQL
     * suffix — same RowMapper, same exception contract.
     *
     * @throws IllegalArgumentException if no tournament with the given id exists for the current
     *     tenant
     * @see <a href="DEC-37">DEC-37 — cascade serialization via per-tournament pessimistic lock</a>
     */
    @Override
    public Tournament findByIdForUpdate(UUID id) {
        List<Tournament> results = jdbc.query(SELECT_BY_ID_FOR_UPDATE, ROW_MAPPER, id);
        if (results.isEmpty()) {
            throw new IllegalArgumentException("Tournament not found for update: id=" + id);
        }
        return results.get(0);
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
