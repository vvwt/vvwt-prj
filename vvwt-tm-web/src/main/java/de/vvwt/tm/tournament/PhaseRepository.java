package de.vvwt.tm.tournament;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Boundary-API tenant-scoped repository for {@link Phase} entities (DEC-21 public API surface).
 *
 * <p>Boundary-API per inventory line 298 — consumed by {@code slotopt/DirectSlotOptimizationClient}
 * and slotopt-integration context.
 *
 * <p>Uses plain {@link JdbcTemplate} (not Spring Data JDBC {@code CrudRepository}) to avoid
 * entity-mapping conflicts with the legacy {@code de.vvwt.tm.domain.Phase} entity that maps to the
 * same {@code phase} table during the reconstruction-in-place phase (DEC-21/DEC-22). The {@link
 * PhaseCrudRepository} will be activated at E21S13 cutover.
 *
 * <p>Tenant scoping is enforced for all queries via the injected tenant UUID.
 *
 * @see Phase
 * @see PhaseCrudRepository
 * @see <a href="DEC-21">DEC-21 — Spring Modulith, root package = public API surface</a>
 * @see <a href="DEC-22">DEC-22 — TDD reconstruction-in-place</a>
 * @see <a href="DEC-26">DEC-26 — DAO test governance (three rules)</a>
 * @see <a href="E21S03">E21S03 — Phase cluster reconstruction (inventory line 298)</a>
 */
@Repository("tmPhaseRepository")
public class PhaseRepository {

    private final JdbcTemplate jdbc;
    private final UUID tenantId;

    private static final String INSERT_SQL =
            "INSERT INTO phase"
                    + " (id, tenant_id, tournament_id, sequence_number, description, status,"
                    + " current_lap_number)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_SQL =
            "UPDATE phase"
                    + " SET description=?, status=?, current_lap_number=?"
                    + " WHERE id=? AND tenant_id=?";

    private static final String SELECT_BY_ID = "SELECT * FROM phase WHERE id=? AND tenant_id=?";

    private static final String SELECT_ALL = "SELECT * FROM phase WHERE tenant_id=?";

    private static final String SELECT_BY_TOURNAMENT =
            "SELECT * FROM phase WHERE tournament_id=? AND tenant_id=?";

    private static final String DELETE_BY_ID = "DELETE FROM phase WHERE id=? AND tenant_id=?";

    private static final String EXISTS_BY_ID =
            "SELECT COUNT(*) FROM phase WHERE id=? AND tenant_id=?";

    /**
     * Primary constructor — used by the Spring context with a {@link
     * de.vvwt.tm.domain.repo.TenantContext} resolver.
     *
     * <p>For production wiring see {@link PhaseRepository#PhaseRepository(JdbcTemplate, UUID)}. For
     * tests, use the DataSource constructor.
     */
    public PhaseRepository(DataSource dataSource, UUID tenantId) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tenantId = tenantId;
    }

    /**
     * Saves a {@link Phase}. Inserts if new, updates otherwise. The entity's {@code tenantId} is
     * set to the injected tenant before insert.
     *
     * @param phase the phase to save (id must be set by caller)
     * @return the saved phase
     */
    public Phase save(Phase phase) {
        phase.setTenantId(tenantId);
        Integer count = jdbc.queryForObject(EXISTS_BY_ID, Integer.class, phase.getId(), tenantId);
        boolean exists = count != null && count > 0;
        if (exists) {
            jdbc.update(
                    UPDATE_SQL,
                    phase.getDescription(),
                    phase.getStatus(),
                    phase.getCurrentLapNumber(),
                    phase.getId(),
                    tenantId);
        } else {
            jdbc.update(
                    INSERT_SQL,
                    phase.getId(),
                    tenantId,
                    phase.getTournamentId(),
                    phase.getSequenceNumber(),
                    phase.getDescription(),
                    phase.getStatus(),
                    phase.getCurrentLapNumber());
        }
        return phase;
    }

    /**
     * Returns the phase with the given id, scoped to the current tenant.
     *
     * @param id the phase UUID
     * @return Optional containing the phase if found and in tenant scope, empty otherwise
     */
    public Optional<Phase> findById(UUID id) {
        List<Phase> results = jdbc.query(SELECT_BY_ID, ROW_MAPPER, id, tenantId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Returns all phases for the current tenant.
     *
     * @return list of phases; never null
     */
    public List<Phase> findAll() {
        return jdbc.query(SELECT_ALL, ROW_MAPPER, tenantId);
    }

    /**
     * Returns all phases belonging to the given tournament that belong to the active tenant.
     *
     * <p>Consumed by slot-optimization and phase services to list phases for match generation.
     *
     * @param tournamentId the tournament to query
     * @return list of phases for the given tournament scoped to the active tenant; never null
     */
    public List<Phase> findByTournamentId(UUID tournamentId) {
        return jdbc.query(SELECT_BY_TOURNAMENT, ROW_MAPPER, tournamentId, tenantId);
    }

    /**
     * Deletes the phase with the given id, scoped to the current tenant.
     *
     * @param id the phase UUID
     */
    public void deleteById(UUID id) {
        jdbc.update(DELETE_BY_ID, id, tenantId);
    }

    // -------------------------------------------------------------------------
    // Row mapper
    // -------------------------------------------------------------------------

    private static final RowMapper<Phase> ROW_MAPPER = (rs, rowNum) -> mapRow(rs);

    private static Phase mapRow(ResultSet rs) throws SQLException {
        Phase p = new Phase();
        p.setId(rs.getObject("id", UUID.class));
        p.setTenantId(rs.getObject("tenant_id", UUID.class));
        p.setTournamentId(rs.getObject("tournament_id", UUID.class));
        p.setSequenceNumber(rs.getInt("sequence_number"));
        p.setDescription(rs.getString("description"));
        p.setStatus(rs.getString("status"));
        p.setCurrentLapNumber(rs.getInt("current_lap_number"));
        p.setCreatedAt(
                rs.getObject("created_at") != null
                        ? rs.getTimestamp("created_at").toLocalDateTime()
                        : null);
        return p;
    }
}
