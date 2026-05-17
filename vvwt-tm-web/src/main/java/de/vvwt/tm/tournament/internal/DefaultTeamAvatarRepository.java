// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.TeamAvatar;
import de.vvwt.tm.tournament.TeamAvatarRepository;
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
 * Default implementation of {@link TeamAvatarRepository}.
 *
 * <p>Tenant-scoped repository for {@link TeamAvatar} entities (DEC-21 public API surface). Uses
 * plain {@link JdbcTemplate} during reconstruction-in-place (DEC-21/DEC-22).
 *
 * @see TeamAvatar
 * @since E57S01 (DEC-58/DEC-72 interface extraction: renamed from TeamAvatarRepository, moved to
 *     tournament.internal, implements {@link TeamAvatarRepository})
 */
@Repository("tmTeamAvatarRepository")
class DefaultTeamAvatarRepository implements TeamAvatarRepository {

    private final JdbcTemplate jdbc;

    private static final String INSERT_SQL =
            "INSERT INTO team_avatar"
                    + " (id, tournament_id, phase_id, group_number, group_position,"
                    + " team_id, description, created_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SELECT_BY_ID = "SELECT * FROM team_avatar WHERE id=?";

    private static final String SELECT_BY_TEAM_ID =
            "SELECT * FROM team_avatar WHERE team_id=? ORDER BY group_number ASC,"
                    + " group_position ASC";

    private static final String SELECT_BY_TOURNAMENT_AND_PHASE =
            "SELECT * FROM team_avatar WHERE tournament_id=? AND phase_id=?"
                    + " ORDER BY group_number ASC, group_position ASC";

    private static final String SELECT_BY_PHASE =
            "SELECT * FROM team_avatar WHERE phase_id=?"
                    + " ORDER BY group_number ASC, group_position ASC";

    private static final String DELETE_BY_ID = "DELETE FROM team_avatar WHERE id=?";

    private static final String EXISTS_BY_ID = "SELECT COUNT(*) FROM team_avatar WHERE id=?";

    private static final String SELECT_BY_PHASE_AND_STRUCTURAL_KEY =
            "SELECT * FROM team_avatar"
                    + " WHERE phase_id=? AND group_number=? AND group_position=?";

    private static final String UPDATE_TEAM_ID = "UPDATE team_avatar SET team_id=? WHERE id=?";

    DefaultTeamAvatarRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public TeamAvatar save(TeamAvatar avatar) {
        Integer count = jdbc.queryForObject(EXISTS_BY_ID, Integer.class, avatar.getId());
        boolean exists = count != null && count > 0;

        if (!exists) {
            jdbc.update(
                    INSERT_SQL,
                    avatar.getId(),
                    avatar.getTournamentId(),
                    avatar.getPhaseId(),
                    avatar.getGroupNumber(),
                    avatar.getGroupPosition(),
                    avatar.getTeamId(),
                    avatar.getDescription(),
                    avatar.getCreatedAt() != null ? avatar.getCreatedAt() : LocalDateTime.now());
        }
        return avatar;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<TeamAvatar> findById(UUID id) {
        List<TeamAvatar> results = jdbc.query(SELECT_BY_ID, ROW_MAPPER, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /** {@inheritDoc} */
    @Override
    public List<TeamAvatar> findByTeamId(UUID teamId) {
        return jdbc.query(SELECT_BY_TEAM_ID, ROW_MAPPER, teamId);
    }

    /** {@inheritDoc} */
    @Override
    public List<TeamAvatar> findByTournamentIdAndPhaseId(UUID tournamentId, UUID phaseId) {
        return jdbc.query(SELECT_BY_TOURNAMENT_AND_PHASE, ROW_MAPPER, tournamentId, phaseId);
    }

    /** {@inheritDoc} */
    @Override
    public List<TeamAvatar> findByPhaseId(UUID phaseId) {
        return jdbc.query(SELECT_BY_PHASE, ROW_MAPPER, phaseId);
    }

    /** {@inheritDoc} */
    @Override
    public void deleteById(UUID id) {
        jdbc.update(DELETE_BY_ID, id);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<TeamAvatar> findByPhaseIdAndGroupNumberAndGroupPosition(
            UUID phaseId, int groupNumber, int groupPosition) {
        List<TeamAvatar> results =
                jdbc.query(
                        SELECT_BY_PHASE_AND_STRUCTURAL_KEY,
                        ROW_MAPPER,
                        phaseId,
                        groupNumber,
                        groupPosition);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /** {@inheritDoc} */
    @Override
    public void updateTeamId(TeamAvatar avatar) {
        jdbc.update(UPDATE_TEAM_ID, avatar.getTeamId(), avatar.getId());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Iterates {@link #save(TeamAvatar)} for each avatar. Used by {@code
     * DefaultDraftService.persistStructuralAvatars()} as its production callsite (DEC-70, E58S02
     * AC7).
     */
    @Override
    public List<TeamAvatar> saveAll(List<TeamAvatar> avatars) {
        java.util.Objects.requireNonNull(avatars, "avatars must not be null");
        List<TeamAvatar> saved = new java.util.ArrayList<>(avatars.size());
        for (TeamAvatar avatar : avatars) {
            saved.add(save(avatar));
        }
        return saved;
    }

    private static final RowMapper<TeamAvatar> ROW_MAPPER = (rs, rowNum) -> mapRow(rs);

    @SuppressWarnings("try")
    private static TeamAvatar mapRow(ResultSet rs) throws SQLException {
        TeamAvatar a = new TeamAvatar();
        a.setId(rs.getObject("id", UUID.class));
        a.setTournamentId(rs.getObject("tournament_id", UUID.class));
        a.setPhaseId(rs.getObject("phase_id", UUID.class));
        a.setGroupNumber(rs.getInt("group_number"));
        a.setGroupPosition(rs.getInt("group_position"));
        a.setTeamId(rs.getObject("team_id", UUID.class));
        a.setDescription(rs.getString("description"));
        a.setCreatedAt(
                rs.getObject("created_at") != null
                        ? rs.getTimestamp("created_at").toLocalDateTime()
                        : null);
        return a;
    }
}
