// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.PhaseBreak;
import de.vvwt.tm.tournament.PhaseBreakRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Default implementation of {@link PhaseBreakRepository}.
 *
 * <p>Boundary-API tenant-scoped repository for {@link PhaseBreak} entities (DEC-21 public API).
 * Uses plain {@link JdbcTemplate} to avoid entity-mapping conflicts during the
 * reconstruction-in-place phase.
 *
 * @see PhaseBreak
 * @since E57S01 (DEC-58/DEC-72 interface extraction: renamed from PhaseBreakRepository, moved to
 *     tournament.internal, implements {@link PhaseBreakRepository})
 */
@Repository("tmPhaseBreakRepository")
class DefaultPhaseBreakRepository implements PhaseBreakRepository {

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

    DefaultPhaseBreakRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public PhaseBreak save(PhaseBreak phaseBreak) {
        Integer count = jdbc.queryForObject(EXISTS_BY_ID, Integer.class, phaseBreak.getId());
        if (count != null && count > 0) {
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

    /** {@inheritDoc} */
    @Override
    public List<PhaseBreak> findByPhaseId(UUID phaseId) {
        return jdbc.query(SELECT_BY_PHASE, ROW_MAPPER, phaseId);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<PhaseBreak> findByPhaseIdAndAfterLapNumber(UUID phaseId, int afterLapNumber) {
        List<PhaseBreak> results =
                jdbc.query(SELECT_BY_PHASE_AND_LAP, ROW_MAPPER, phaseId, afterLapNumber);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<PhaseBreak> findById(UUID id) {
        List<PhaseBreak> results = jdbc.query(SELECT_BY_ID, ROW_MAPPER, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /** {@inheritDoc} */
    @Override
    public void deleteById(UUID id) {
        jdbc.update(DELETE_BY_ID, id);
    }

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
