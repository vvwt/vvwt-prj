package de.vvwt.tm.tournament;

import de.vvwt.tm.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Boundary-API tenant-scoped repository for {@link RoundSnapshot} entities (DEC-21 public API).
 *
 * <p>Boundary-API elevation per inventory line 300 — elevated to match {@link RoundSnapshot}
 * entity's public-API status (consumed by display and print contexts). Per Brief D-4/D-8 refinement
 * decision.
 *
 * <p>Uses plain {@link JdbcTemplate} to avoid entity-mapping conflicts with legacy {@code
 * de.vvwt.tm.domain.RoundSnapshot} during the reconstruction-in-place phase (DEC-21/DEC-22). The
 * {@link RoundSnapshotCrudRepository} will be activated at E21S13 cutover.
 *
 * @see RoundSnapshot
 * @see RoundSnapshotCrudRepository
 * @see <a href="DEC-21">DEC-21 — Spring Modulith, root package = public API surface</a>
 * @see <a href="DEC-22">DEC-22 — TDD reconstruction-in-place</a>
 * @see <a href="DEC-26">DEC-26 — DAO test governance (three rules)</a>
 * @see <a href="E21S03">E21S03 — Phase cluster reconstruction (inventory line 300)</a>
 */
@Repository("tmRoundSnapshotRepository")
public class RoundSnapshotRepository {

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;

    private static final String INSERT_SQL =
            "INSERT INTO round_snapshots"
                    + " (id, tenant_id, tournament_id, phase_id, lap_number, snapshot_payload)"
                    + " VALUES (?, ?, ?, ?, ?, ?)";

    private static final String SELECT_BY_ID =
            "SELECT * FROM round_snapshots WHERE id=? AND tenant_id=?";

    private static final String SELECT_ALL = "SELECT * FROM round_snapshots WHERE tenant_id=?";

    private static final String DELETE_BY_ID =
            "DELETE FROM round_snapshots WHERE id=? AND tenant_id=?";

    private static final String EXISTS_BY_ID =
            "SELECT COUNT(*) FROM round_snapshots WHERE id=? AND tenant_id=?";

    public RoundSnapshotRepository(JdbcTemplate jdbc, TenantContext tenantContext) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
    }

    /**
     * Saves a {@link RoundSnapshot}. Inserts if new. The entity's {@code tenantId} is set to the
     * injected tenant before insert.
     *
     * <p>RoundSnapshots are immutable after creation (no update path for E21S03 scope).
     *
     * @param snapshot the snapshot to save
     * @return the saved snapshot
     */
    public RoundSnapshot save(RoundSnapshot snapshot) {
        UUID tenantId = tenantContext.current();
        snapshot.setTenantId(tenantId);
        Integer count =
                jdbc.queryForObject(EXISTS_BY_ID, Integer.class, snapshot.getId(), tenantId);
        if (count != null && count > 0) {
            return snapshot; // immutable — no update
        }
        jdbc.update(
                INSERT_SQL,
                snapshot.getId(),
                tenantId,
                snapshot.getTournamentId(),
                snapshot.getPhaseId(),
                snapshot.getLapNumber(),
                snapshot.getSnapshotPayload());
        return snapshot;
    }

    /**
     * Returns the snapshot with the given id, scoped to the current tenant.
     *
     * @param id the snapshot UUID
     * @return Optional containing the snapshot if found and in tenant scope, empty otherwise
     */
    public Optional<RoundSnapshot> findById(UUID id) {
        UUID tenantId = tenantContext.current();
        List<RoundSnapshot> results = jdbc.query(SELECT_BY_ID, ROW_MAPPER, id, tenantId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Returns all snapshots for the current tenant.
     *
     * @return list of snapshots; never null
     */
    public List<RoundSnapshot> findAll() {
        UUID tenantId = tenantContext.current();
        return jdbc.query(SELECT_ALL, ROW_MAPPER, tenantId);
    }

    /**
     * Deletes the snapshot with the given id, scoped to the current tenant.
     *
     * @param id the snapshot UUID
     */
    public void deleteById(UUID id) {
        UUID tenantId = tenantContext.current();
        jdbc.update(DELETE_BY_ID, id, tenantId);
    }

    // -------------------------------------------------------------------------
    // Row mapper
    // -------------------------------------------------------------------------

    private static final RowMapper<RoundSnapshot> ROW_MAPPER = (rs, rowNum) -> mapRow(rs);

    private static RoundSnapshot mapRow(ResultSet rs) throws SQLException {
        RoundSnapshot s = new RoundSnapshot();
        s.setId(rs.getObject("id", UUID.class));
        s.setTenantId(rs.getObject("tenant_id", UUID.class));
        s.setTournamentId(rs.getObject("tournament_id", UUID.class));
        s.setPhaseId(rs.getObject("phase_id", UUID.class));
        s.setLapNumber(rs.getInt("lap_number"));
        s.setSnapshotPayload(rs.getString("snapshot_payload"));
        s.setCreatedAt(
                rs.getObject("created_at") != null
                        ? rs.getTimestamp("created_at").toLocalDateTime()
                        : null);
        return s;
    }
}
