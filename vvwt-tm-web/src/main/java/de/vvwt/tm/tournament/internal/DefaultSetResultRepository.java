// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.SetResult;
import de.vvwt.tm.tournament.SetResultRepository;
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
 * Default implementation of {@link SetResultRepository}.
 *
 * <p>Tenant-scoped repository for {@link SetResult} entities (DEC-21, DEC-22, DEC-26, E21S05).
 * Boundary-API per inventory line 301. Uses {@link JdbcTemplate} directly.
 *
 * @see SetResult
 * @since E57S01 (DEC-58/DEC-72 interface extraction: renamed from SetResultRepository, moved to
 *     tournament.internal, implements {@link SetResultRepository})
 */
@Repository("tmSetResultRepository")
class DefaultSetResultRepository implements SetResultRepository {

    private static final String INSERT_SQL =
            "INSERT INTO set_result (match_id, set_index, phase_id, team1_points,"
                    + " team2_points, set_state, change_time, created_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";

    private static final String UPDATE_SQL =
            "UPDATE set_result SET team1_points=?, team2_points=?, set_state=?,"
                    + " change_time=CURRENT_TIMESTAMP"
                    + " WHERE match_id=? AND set_index=?";

    private static final String SELECT_BY_MATCH = "SELECT * FROM set_result WHERE match_id=?";

    private static final String SELECT_BY_PK =
            "SELECT * FROM set_result WHERE match_id=? AND set_index=?";

    private static final String DELETE_BY_PK =
            "DELETE FROM set_result WHERE match_id=? AND set_index=?";

    private final JdbcTemplate jdbc;

    DefaultSetResultRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public void insert(SetResult setResult) {
        jdbc.update(
                INSERT_SQL,
                setResult.getMatchId(),
                setResult.getSetIndex(),
                setResult.getPhaseId(),
                setResult.getTeam1Points(),
                setResult.getTeam2Points(),
                setResult.getSetStateCode());
    }

    /** {@inheritDoc} */
    @Override
    public void update(SetResult setResult) {
        jdbc.update(
                UPDATE_SQL,
                setResult.getTeam1Points(),
                setResult.getTeam2Points(),
                setResult.getSetStateCode(),
                setResult.getMatchId(),
                setResult.getSetIndex());
    }

    /** {@inheritDoc} */
    @Override
    public List<SetResult> findByMatchId(UUID matchId) {
        return jdbc.query(SELECT_BY_MATCH, ROW_MAPPER, matchId);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<SetResult> findByMatchIdAndSetIndex(UUID matchId, int setIndex) {
        List<SetResult> results = jdbc.query(SELECT_BY_PK, ROW_MAPPER, matchId, setIndex);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /** {@inheritDoc} */
    @Override
    public void deleteByMatchIdAndSetIndex(UUID matchId, int setIndex) {
        jdbc.update(DELETE_BY_PK, matchId, setIndex);
    }

    private static final RowMapper<SetResult> ROW_MAPPER = DefaultSetResultRepository::mapRow;

    private static SetResult mapRow(ResultSet rs, int rowNum) throws SQLException {
        SetResult sr = new SetResult();
        sr.setMatchId(rs.getObject("match_id", UUID.class));
        sr.setSetIndex(rs.getInt("set_index"));
        sr.setPhaseId(rs.getObject("phase_id", UUID.class));
        sr.setTeam1Points(rs.getInt("team1_points"));
        sr.setTeam2Points(rs.getInt("team2_points"));
        sr.setSetStateCode(rs.getInt("set_state"));
        sr.setChangeTime(rs.getObject("change_time", LocalDateTime.class));
        sr.setCreatedAt(rs.getObject("created_at", LocalDateTime.class));
        return sr;
    }
}
