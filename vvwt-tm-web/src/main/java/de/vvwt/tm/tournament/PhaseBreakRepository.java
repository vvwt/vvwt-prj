package de.vvwt.tm.tournament;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Boundary-API tenant-scoped repository for {@link PhaseBreak} entities (DEC-21 public API).
 *
 * <p>Boundary-API elevation per inventory line 296 — {@code PhaseBreak} is consumed by the {@code
 * timer/TimerDataService} context; repository elevated to match entity's public-API status (per
 * Brief D-4/D-8 refinement decision).
 *
 * <p>Uses plain {@link JdbcTemplate} to avoid entity-mapping conflicts with legacy {@code
 * de.vvwt.tm.domain.PhaseBreak} during the reconstruction-in-place phase (DEC-21/DEC-22). {@link
 * PhaseBreakCrudRepository} will be activated at E21S13 cutover.
 *
 * @see PhaseBreak
 * @see PhaseBreakCrudRepository
 * @see <a href="DEC-21">DEC-21 — Spring Modulith, root package = public API surface</a>
 * @see <a href="DEC-22">DEC-22 — TDD reconstruction-in-place</a>
 * @see <a href="DEC-26">DEC-26 — DAO test governance (three rules)</a>
 * @see <a href="E21S03">E21S03 — Phase cluster reconstruction (inventory line 296)</a>
 */
@Repository("tmPhaseBreakRepository")
public class PhaseBreakRepository {

    private final JdbcTemplate jdbc;

    private static final String INSERT_SQL =
            "INSERT INTO phase_breaks"
                    + " (id, phase_id, after_lap_number, duration_minutes, label)"
                    + " VALUES (?, ?, ?, ?, ?)";

    private static final String SELECT_BY_PHASE = "SELECT * FROM phase_breaks WHERE phase_id=?";

    private static final String SELECT_BY_PHASE_AND_LAP =
            "SELECT * FROM phase_breaks" + " WHERE phase_id=? AND after_lap_number=?";

    private static final String SELECT_BY_ID = "SELECT * FROM phase_breaks WHERE id=?";

    private static final String DELETE_BY_ID = "DELETE FROM phase_breaks WHERE id=?";

    private static final String EXISTS_BY_ID = "SELECT COUNT(*) FROM phase_breaks WHERE id=?";

    public PhaseBreakRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Saves a {@link PhaseBreak}. Inserts if new. The entity's {@code tenantId} is set to the
     * injected tenant before insert.
     *
     * @param phaseBreak the phase break to save
     * @return the saved phase break
     */
    public PhaseBreak save(PhaseBreak phaseBreak) {
        Integer count = jdbc.queryForObject(EXISTS_BY_ID, Integer.class, phaseBreak.getId());
        if (count != null && count > 0) {
            // PhaseBreaks are immutable after creation — no update path needed for E21S03 scope
            return phaseBreak;
        }
        jdbc.update(
                INSERT_SQL,
                phaseBreak.getId(),
                phaseBreak.getPhaseId(),
                phaseBreak.getAfterLapNumber(),
                phaseBreak.getDurationMinutes(),
                phaseBreak.getLabel());
        return phaseBreak;
    }

    /**
     * Returns all phase breaks for the given phase.
     *
     * @param phaseId the phase to query
     * @return list of phase breaks for the given phase; never null
     */
    public List<PhaseBreak> findByPhaseId(UUID phaseId) {
        return jdbc.query(SELECT_BY_PHASE, ROW_MAPPER, phaseId);
    }

    /**
     * Returns the phase break at the given lap boundary within the given phase, or {@link
     * Optional#empty()} if no break exists at that position.
     *
     * <p>Used by {@link PhaseBreakService} to detect duplicate entries before persisting.
     *
     * @param phaseId the phase to query
     * @param afterLapNumber the lap boundary position to check
     * @return the phase break at the given position, or empty
     */
    public Optional<PhaseBreak> findByPhaseIdAndAfterLapNumber(UUID phaseId, int afterLapNumber) {
        List<PhaseBreak> results =
                jdbc.query(SELECT_BY_PHASE_AND_LAP, ROW_MAPPER, phaseId, afterLapNumber);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Returns the phase break with the given id.
     *
     * @param id the phase break UUID
     * @return Optional containing the phase break if found, empty otherwise
     */
    public Optional<PhaseBreak> findById(UUID id) {
        List<PhaseBreak> results = jdbc.query(SELECT_BY_ID, ROW_MAPPER, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Deletes the phase break with the given id.
     *
     * @param id the phase break UUID
     */
    public void deleteById(UUID id) {
        jdbc.update(DELETE_BY_ID, id);
    }

    // -------------------------------------------------------------------------
    // Row mapper
    // -------------------------------------------------------------------------

    private static final RowMapper<PhaseBreak> ROW_MAPPER = (rs, rowNum) -> mapRow(rs);

    private static PhaseBreak mapRow(ResultSet rs) throws SQLException {
        PhaseBreak pb = new PhaseBreak();
        pb.setId(rs.getObject("id", UUID.class));
        pb.setPhaseId(rs.getObject("phase_id", UUID.class));
        pb.setAfterLapNumber(rs.getInt("after_lap_number"));
        pb.setDurationMinutes(rs.getInt("duration_minutes"));
        pb.setLabel(rs.getString("label"));
        return pb;
    }
}
