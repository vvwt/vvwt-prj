package de.vvwt.tm.tournament.internal;

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
 * Append-only, tenant-scoped repository for {@link AuditLogEntry} entities (DEC-21, DEC-22, DEC-26,
 * E21S05).
 *
 * <p>INTERNAL to the {@code tournament} Modulith context per inventory line 287. No cross-context
 * code should import this repository directly (it is in the {@code internal} package, protected by
 * Modulith boundary enforcement).
 *
 * <h2>JdbcTemplate (not Spring Data CrudRepository delegation)</h2>
 *
 * <p>Uses plain {@link JdbcTemplate} to avoid entity-mapping conflicts during
 * reconstruction-in-place (DEC-21/DEC-22). Both the new {@code
 * de.vvwt.tm.tournament.internal.AuditLogEntry} and the legacy {@code
 * de.vvwt.tm.domain.AuditLogEntry} are mapped to {@code @Table("audit_log")}. Spring Data JDBC's
 * auto-registration of any {@code CrudRepository} for either entity causes bean-override collisions
 * that break the legacy {@code de.vvwt.tm.domain.repo.AuditLogRepository}. JdbcTemplate avoids this
 * entirely — same pattern as {@link de.vvwt.tm.tournament.SetResultRepository}, {@link
 * de.vvwt.tm.tournament.MatchOutcomeRepository}.
 *
 * <h2>Append-only guarantee</h2>
 *
 * <p>The only write method is {@link #save(AuditLogEntry)}. {@link #deleteById(UUID)} throws {@link
 * UnsupportedOperationException}.
 *
 * <p>Bean qualifier {@code "tmAuditLogRepository"} avoids collision with legacy {@code
 * de.vvwt.tm.domain.repo.AuditLogRepository}.
 *
 * @see AuditLogEntry
 * @see AuditLogCrudRepository
 * @see <a href="DEC-21">DEC-21 — internal package discipline</a>
 * @see <a href="DEC-22">DEC-22 — TDD Iron Law</a>
 * @see <a href="DEC-26">DEC-26 — DAO test governance</a>
 * @see <a href="E21S05">E21S05 — inventory line 287</a>
 */
@Repository("tmAuditLogRepository")
public class AuditLogRepository {

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

    public AuditLogRepository(JdbcTemplate jdbc, TenantContext tenantContext) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
    }

    /**
     * Appends a new {@link AuditLogEntry} row. Tenant scoping is enforced — the entity's tenantId
     * is set to the current tenant before insert.
     *
     * @param entry the entry to append (id must be set by caller)
     * @return the saved entry
     * @throws IllegalStateException if no tenant context is active
     */
    public AuditLogEntry save(AuditLogEntry entry) {
        UUID currentTenantId = tenantContext.current(); // guard fires here
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

    /**
     * Returns the audit log entry for the given id, scoped to the current tenant.
     *
     * @param id the entry UUID
     * @return Optional.of(entry) if found and belongs to active tenant, Optional.empty() otherwise
     * @throws IllegalStateException if no tenant context is active
     */
    public Optional<AuditLogEntry> findById(UUID id) {
        UUID tenantId = tenantContext.current(); // guard fires here
        List<AuditLogEntry> results = jdbc.query(SELECT_BY_ID, ROW_MAPPER, id, tenantId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Returns audit entries for a given match and set index in chronological order, scoped to the
     * active tenant.
     *
     * @param matchId the match whose audit entries to retrieve
     * @param setIndex the set index within the match
     * @return list of audit entries in chronological order; never {@code null}
     * @throws IllegalStateException if no tenant context is active
     */
    public List<AuditLogEntry> findByMatchIdAndSetIndexOrderByChangedAt(
            UUID matchId, int setIndex) {
        UUID tenantId = tenantContext.current(); // guard fires here
        return jdbc.query(SELECT_BY_MATCH_SET, ROW_MAPPER, matchId, setIndex, tenantId);
    }

    /**
     * Deleting audit log entries is FORBIDDEN (append-only invariant).
     *
     * @param id the ID (ignored)
     * @throws UnsupportedOperationException always — audit log is append-only per DEC-22/E21S05
     */
    public void deleteById(UUID id) {
        throw new UnsupportedOperationException(
                "AuditLogRepository is append-only — deleteById is forbidden. E21S05.");
    }

    // -------------------------------------------------------------------------
    // Row mapper
    // -------------------------------------------------------------------------

    private static final RowMapper<AuditLogEntry> ROW_MAPPER = AuditLogRepository::mapRow;

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
