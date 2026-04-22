package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tenant.TenantContext;
import de.vvwt.tm.tournament.AuditLogEntry;
import de.vvwt.tm.tournament.AuditLogRepository;
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
 * Default implementation of {@link AuditLogRepository} (DEC-35, E31S01).
 *
 * <p>Uses plain {@link JdbcTemplate} to avoid entity-mapping conflicts with the legacy {@code
 * de.vvwt.tm.domain.AuditLogEntry} during reconstruction-in-place (DEC-21/DEC-22). Both the new
 * {@code de.vvwt.tm.tournament.AuditLogEntry} and the legacy entity map to
 * {@code @Table("audit_log")} — JdbcTemplate avoids auto-registration collisions.
 *
 * <p>Append-only guarantee: the only write method is {@link #save(AuditLogEntry)}. {@link
 * #deleteById(UUID)} throws {@link UnsupportedOperationException}.
 *
 * <p>Bean qualifier {@code "tmAuditLogRepository"} avoids collision with the legacy {@code
 * de.vvwt.tm.domain.repo.AuditLogRepository}.
 *
 * @see AuditLogRepository
 * @see AuditLogEntry
 * @see AuditLogCrudRepository
 * @see <a href="DEC-21">DEC-21 — internal package discipline</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-26">DEC-26 — DAO test governance</a>
 * @see <a href="DEC-35">DEC-35 — impl in internal</a>
 * @see <a href="E21S05">E21S05 — inventory line 287</a>
 * @see <a href="E31S01">E31S01 — interface extraction (MANDATORY)</a>
 */
@Repository("tmAuditLogRepository")
public class DefaultAuditLogRepository implements AuditLogRepository {

    private static final String INSERT_SQL =
            "INSERT INTO audit_log (id, tenant_id, match_id, set_index,"
                    + " team1_points_old, team2_points_old, set_state_old,"
                    + " team1_points_new, team2_points_new, set_state_new,"
                    + " actor_id, reason, source_type, source_device_id)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SELECT_BY_ID =
            "SELECT * FROM audit_log WHERE id = ? AND tenant_id = ?";

    private static final String SELECT_BY_MATCH_SET =
            "SELECT * FROM audit_log WHERE match_id = ? AND set_index = ? AND tenant_id = ?"
                    + " ORDER BY changed_at ASC";

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;

    public DefaultAuditLogRepository(JdbcTemplate jdbc, TenantContext tenantContext) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Tenant scoping is enforced — the entity's tenantId is set to the current tenant before
     * insert.
     */
    @Override
    public AuditLogEntry save(AuditLogEntry entry) {
        UUID currentTenantId = tenantContext.current();
        entry.setTenantId(currentTenantId);
        jdbc.update(
                INSERT_SQL,
                entry.getId(),
                currentTenantId,
                entry.getMatchId(),
                entry.getSetIndex(),
                entry.getTeam1PointsOld(),
                entry.getTeam2PointsOld(),
                entry.getSetStateOld(),
                entry.getTeam1PointsNew(),
                entry.getTeam2PointsNew(),
                entry.getSetStateNew(),
                entry.getActorId(),
                entry.getReason(),
                entry.getSourceType(),
                entry.getSourceDeviceId());
        return entry;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<AuditLogEntry> findById(UUID id) {
        UUID tenantId = tenantContext.current();
        List<AuditLogEntry> results = jdbc.query(SELECT_BY_ID, ROW_MAPPER, id, tenantId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /** {@inheritDoc} */
    @Override
    public List<AuditLogEntry> findByMatchIdAndSetIndexOrderByChangedAt(
            UUID matchId, int setIndex) {
        UUID tenantId = tenantContext.current();
        return jdbc.query(SELECT_BY_MATCH_SET, ROW_MAPPER, matchId, setIndex, tenantId);
    }

    /**
     * {@inheritDoc}
     *
     * @throws UnsupportedOperationException always — audit log is append-only per DEC-22/E21S05
     */
    @Override
    public void deleteById(UUID id) {
        throw new UnsupportedOperationException(
                "AuditLogRepository is append-only — deleteById is forbidden. E21S05.");
    }

    // -------------------------------------------------------------------------
    // Row mapper
    // -------------------------------------------------------------------------

    private static final RowMapper<AuditLogEntry> ROW_MAPPER = DefaultAuditLogRepository::mapRow;

    private static AuditLogEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
        AuditLogEntry e = new AuditLogEntry();
        e.setId(rs.getObject("id", UUID.class));
        e.setTenantId(rs.getObject("tenant_id", UUID.class));
        e.setMatchId(rs.getObject("match_id", UUID.class));
        e.setSetIndex(rs.getInt("set_index"));
        e.setTeam1PointsOld(getBoxedInt(rs, "team1_points_old"));
        e.setTeam2PointsOld(getBoxedInt(rs, "team2_points_old"));
        e.setSetStateOld(getBoxedInt(rs, "set_state_old"));
        e.setTeam1PointsNew(rs.getInt("team1_points_new"));
        e.setTeam2PointsNew(rs.getInt("team2_points_new"));
        e.setSetStateNew(rs.getInt("set_state_new"));
        e.setActorId(rs.getString("actor_id"));
        e.setReason(rs.getString("reason"));
        e.setChangedAt(rs.getObject("changed_at", LocalDateTime.class));
        e.setSourceType(rs.getString("source_type"));
        e.setSourceDeviceId(rs.getString("source_device_id"));
        return e;
    }

    private static Integer getBoxedInt(ResultSet rs, String col) throws SQLException {
        int val = rs.getInt(col);
        return rs.wasNull() ? null : val;
    }
}
