// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

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

    private static final String INSERT_SQL =
            "INSERT INTO phase"
                    + " (id, tournament_id, sequence_number, description, status,"
                    + " current_lap_number, optimized, last_job_state)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_SQL =
            "UPDATE phase"
                    + " SET description=?, status=?, current_lap_number=?,"
                    + " optimized=?, last_job_state=?"
                    + " WHERE id=?";

    private static final String SELECT_BY_ID = "SELECT * FROM phase WHERE id=?";

    private static final String SELECT_ALL = "SELECT * FROM phase";

    private static final String SELECT_BY_TOURNAMENT = "SELECT * FROM phase WHERE tournament_id=?";

    private static final String SELECT_BY_TOURNAMENT_AND_SEQ =
            "SELECT * FROM phase WHERE tournament_id=? AND sequence_number=?";

    private static final String DELETE_BY_ID = "DELETE FROM phase WHERE id=?";

    private static final String EXISTS_BY_ID = "SELECT COUNT(*) FROM phase WHERE id=?";

    /**
     * Column-scoped UPDATE: writes only {@code last_job_state} without touching any other column
     * (E55S09 H-B structural fix per AC-FIX-H-B-PHASE-REPOSITORY-UPDATE-LAST-JOB-STATE-METHOD).
     */
    private static final String UPDATE_LAST_JOB_STATE_SQL =
            "UPDATE phase SET last_job_state=? WHERE id=?";

    public DefaultPhaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public Phase save(Phase phase) {
        Integer count = jdbc.queryForObject(EXISTS_BY_ID, Integer.class, phase.getId());
        boolean exists = count != null && count > 0;
        if (exists) {
            jdbc.update(
                    UPDATE_SQL,
                    phase.getDescription(),
                    phase.getStatus(),
                    phase.getCurrentLapNumber(),
                    phase.isOptimized(),
                    phase.getLastJobState(),
                    phase.getId());
        } else {
            jdbc.update(
                    INSERT_SQL,
                    phase.getId(),
                    phase.getTournamentId(),
                    phase.getSequenceNumber(),
                    phase.getDescription(),
                    phase.getStatus(),
                    phase.getCurrentLapNumber(),
                    phase.isOptimized(),
                    phase.getLastJobState());
        }
        return phase;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Phase> findById(UUID id) {
        List<Phase> results = jdbc.query(SELECT_BY_ID, ROW_MAPPER, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /** {@inheritDoc} */
    @Override
    public List<Phase> findAll() {
        return jdbc.query(SELECT_ALL, ROW_MAPPER);
    }

    /** {@inheritDoc} */
    @Override
    public List<Phase> findByTournamentId(UUID tournamentId) {
        return jdbc.query(SELECT_BY_TOURNAMENT, ROW_MAPPER, tournamentId);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Phase> findByTournamentIdAndSequenceNumber(
            UUID tournamentId, int sequenceNumber) {
        List<Phase> results =
                jdbc.query(SELECT_BY_TOURNAMENT_AND_SEQ, ROW_MAPPER, tournamentId, sequenceNumber);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Executes {@code UPDATE phase SET last_job_state=? WHERE id=?}. If the UPDATE affects 0
     * rows (phase deleted concurrently), this method is a graceful no-op per
     * AC-ERROR-HANDLING-H-B-COLUMN-WRITE-ATOMIC (consistent with {@link
     * de.vvwt.tm.phaselifecycle.internal.DefaultPhaseLastJobStateWriter}'s no-op-on-deleted
     * pattern).
     */
    @Override
    public void updateLastJobState(UUID phaseId, String lastJobState) {
        jdbc.update(UPDATE_LAST_JOB_STATE_SQL, lastJobState, phaseId);
    }

    /** {@inheritDoc} */
    @Override
    public void deleteById(UUID id) {
        jdbc.update(DELETE_BY_ID, id);
    }

    // -------------------------------------------------------------------------
    // Row mapper
    // -------------------------------------------------------------------------

    private static final RowMapper<Phase> ROW_MAPPER = (rs, rowNum) -> mapRow(rs);

    private static Phase mapRow(ResultSet rs) throws SQLException {
        Phase p = new Phase();
        p.setId(rs.getObject("id", UUID.class));
        p.setTournamentId(rs.getObject("tournament_id", UUID.class));
        p.setSequenceNumber(rs.getInt("sequence_number"));
        p.setDescription(rs.getString("description"));
        p.setStatus(rs.getString("status"));
        p.setCurrentLapNumber(rs.getInt("current_lap_number"));
        p.setCreatedAt(
                rs.getObject("created_at") != null
                        ? rs.getTimestamp("created_at").toLocalDateTime()
                        : null);
        p.setOptimized(rs.getBoolean("optimized"));
        p.setLastJobState(rs.getString("last_job_state"));
        return p;
    }
}
