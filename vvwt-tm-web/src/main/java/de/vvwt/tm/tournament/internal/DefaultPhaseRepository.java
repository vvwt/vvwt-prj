package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Default implementation of {@link PhaseRepository} (DEC-35, E31S01).
 *
 * <p>Uses plain {@link JdbcTemplate}. Tenant scoping enforced via active {@link TenantContext}.
 *
 * <p>Bean qualifier {@code "tmPhaseRepository"} preserves injection compatibility with call sites
 * established in E21S03.
 *
 * @see PhaseRepository
 * @see <a href="DEC-35">DEC-35 — package layout: impl in internal</a>
 * @see <a href="E21S03">E21S03 — Phase cluster reconstruction (inventory line 298)</a>
 * @see <a href="E31S01">E31S01 — interface extraction</a>
 */
@Repository("tmPhaseRepository")
public class DefaultPhaseRepository implements PhaseRepository {

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;

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

    public DefaultPhaseRepository(JdbcTemplate jdbc, TenantContext tenantContext) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
    }

    /** {@inheritDoc} */
    @Override
    public Phase save(Phase phase) {
        UUID tenantId = tenantContext.current();
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

    /** {@inheritDoc} */
    @Override
    public Optional<Phase> findById(UUID id) {
        UUID tenantId = tenantContext.current();
        List<Phase> results = jdbc.query(SELECT_BY_ID, ROW_MAPPER, id, tenantId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /** {@inheritDoc} */
    @Override
    public List<Phase> findAll() {
        UUID tenantId = tenantContext.current();
        return jdbc.query(SELECT_ALL, ROW_MAPPER, tenantId);
    }

    /** {@inheritDoc} */
    @Override
    public List<Phase> findByTournamentId(UUID tournamentId) {
        UUID tenantId = tenantContext.current();
        return jdbc.query(SELECT_BY_TOURNAMENT, ROW_MAPPER, tournamentId, tenantId);
    }

    /** {@inheritDoc} */
    @Override
    public void deleteById(UUID id) {
        UUID tenantId = tenantContext.current();
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
