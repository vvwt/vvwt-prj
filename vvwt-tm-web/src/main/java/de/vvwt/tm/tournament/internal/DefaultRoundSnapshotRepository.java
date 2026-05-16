package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.RoundSnapshot;
import de.vvwt.tm.tournament.RoundSnapshotRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Default implementation of {@link RoundSnapshotRepository}.
 *
 * <p>Boundary-API tenant-scoped repository for {@link RoundSnapshot} entities (DEC-21 public API).
 * Uses plain {@link JdbcTemplate} during the reconstruction-in-place phase.
 *
 * @see RoundSnapshot
 * @since E57S01 (DEC-58/DEC-72 interface extraction: renamed from RoundSnapshotRepository, moved to
 *     tournament.internal, implements {@link RoundSnapshotRepository})
 */
@Repository("tmRoundSnapshotRepository")
class DefaultRoundSnapshotRepository implements RoundSnapshotRepository {

    private final JdbcTemplate jdbc;

    private static final String INSERT_SQL =
            "INSERT INTO round_snapshots"
                    + " (id, tournament_id, phase_id, lap_number, snapshot_payload)"
                    + " VALUES (?, ?, ?, ?, ?)";

    private static final String SELECT_BY_ID = "SELECT * FROM round_snapshots WHERE id=?";

    private static final String SELECT_ALL = "SELECT * FROM round_snapshots";

    private static final String DELETE_BY_ID = "DELETE FROM round_snapshots WHERE id=?";

    private static final String EXISTS_BY_ID = "SELECT COUNT(*) FROM round_snapshots WHERE id=?";

    DefaultRoundSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public RoundSnapshot save(RoundSnapshot snapshot) {
        Integer count = jdbc.queryForObject(EXISTS_BY_ID, Integer.class, snapshot.getId());
        if (count != null && count > 0) {
            return snapshot;
        }
        jdbc.update(
                INSERT_SQL,
                snapshot.getId(),
                snapshot.getTournamentId(),
                snapshot.getPhaseId(),
                snapshot.getLapNumber(),
                snapshot.getSnapshotPayload());
        return snapshot;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<RoundSnapshot> findById(UUID id) {
        List<RoundSnapshot> results = jdbc.query(SELECT_BY_ID, ROW_MAPPER, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /** {@inheritDoc} */
    @Override
    public List<RoundSnapshot> findAll() {
        return jdbc.query(SELECT_ALL, ROW_MAPPER);
    }

    /** {@inheritDoc} */
    @Override
    public void deleteById(UUID id) {
        jdbc.update(DELETE_BY_ID, id);
    }

    private static final RowMapper<RoundSnapshot> ROW_MAPPER = (rs, rowNum) -> mapRow(rs);

    private static RoundSnapshot mapRow(ResultSet rs) throws SQLException {
        RoundSnapshot s = new RoundSnapshot();
        s.setId(rs.getObject("id", UUID.class));
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
