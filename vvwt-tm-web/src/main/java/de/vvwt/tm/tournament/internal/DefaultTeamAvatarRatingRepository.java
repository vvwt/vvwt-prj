package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.TeamAvatarRating;
import de.vvwt.tm.tournament.TeamAvatarRatingRepository;
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
 * Default implementation of {@link TeamAvatarRatingRepository}.
 *
 * <p>Tenant-scoped repository for {@link TeamAvatarRating} entities (DEC-21 public API surface).
 * Uses plain {@link JdbcTemplate} to avoid entity-mapping conflicts during reconstruction-in-place.
 *
 * @see TeamAvatarRating
 * @since E57S01 (DEC-58/DEC-72 interface extraction: renamed from TeamAvatarRatingRepository, moved
 *     to tournament.internal, implements {@link TeamAvatarRatingRepository})
 */
@Repository("tmTeamAvatarRatingRepository")
class DefaultTeamAvatarRatingRepository implements TeamAvatarRatingRepository {

    private final JdbcTemplate jdbc;

    private static final String INSERT_SQL =
            "INSERT INTO team_avatar_rating"
                    + " (avatar_id, match_count, set_count, points,"
                    + " sets_won, sets_lost, balls_won, balls_lost,"
                    + " set_quotient, ball_quotient, is_without_assessment, updated_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_SQL =
            "UPDATE team_avatar_rating SET"
                    + " match_count=?, set_count=?, points=?,"
                    + " sets_won=?, sets_lost=?, balls_won=?, balls_lost=?,"
                    + " set_quotient=?, ball_quotient=?, is_without_assessment=?,"
                    + " updated_at=?"
                    + " WHERE avatar_id=?";

    private static final String SELECT_BY_AVATAR_ID =
            "SELECT * FROM team_avatar_rating WHERE avatar_id=?";

    private static final String DELETE_BY_AVATAR_ID =
            "DELETE FROM team_avatar_rating WHERE avatar_id=?";

    private static final String EXISTS_BY_AVATAR_ID =
            "SELECT COUNT(*) FROM team_avatar_rating WHERE avatar_id=?";

    DefaultTeamAvatarRatingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@inheritDoc} */
    @Override
    public TeamAvatarRating save(TeamAvatarRating rating) {
        Integer count =
                jdbc.queryForObject(EXISTS_BY_AVATAR_ID, Integer.class, rating.getAvatarId());
        boolean exists = count != null && count > 0;

        LocalDateTime now = LocalDateTime.now();

        if (exists) {
            jdbc.update(
                    UPDATE_SQL,
                    rating.getMatchCount(),
                    rating.getSetCount(),
                    rating.getPoints(),
                    rating.getSetsWon(),
                    rating.getSetsLost(),
                    rating.getBallsWon(),
                    rating.getBallsLost(),
                    rating.getSetQuotient(),
                    rating.getBallQuotient(),
                    rating.isWithoutAssessment(),
                    now,
                    rating.getAvatarId());
        } else {
            jdbc.update(
                    INSERT_SQL,
                    rating.getAvatarId(),
                    rating.getMatchCount(),
                    rating.getSetCount(),
                    rating.getPoints(),
                    rating.getSetsWon(),
                    rating.getSetsLost(),
                    rating.getBallsWon(),
                    rating.getBallsLost(),
                    rating.getSetQuotient(),
                    rating.getBallQuotient(),
                    rating.isWithoutAssessment(),
                    rating.getUpdatedAt() != null ? rating.getUpdatedAt() : now);
        }
        return rating;
    }

    /** {@inheritDoc} */
    @Override
    public Optional<TeamAvatarRating> findByAvatarId(UUID avatarId) {
        List<TeamAvatarRating> results = jdbc.query(SELECT_BY_AVATAR_ID, ROW_MAPPER, avatarId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<TeamAvatarRating> findById(UUID avatarId) {
        return findByAvatarId(avatarId);
    }

    /** {@inheritDoc} */
    @Override
    public void deleteByAvatarId(UUID avatarId) {
        jdbc.update(DELETE_BY_AVATAR_ID, avatarId);
    }

    private static final RowMapper<TeamAvatarRating> ROW_MAPPER = (rs, rowNum) -> mapRow(rs);

    @SuppressWarnings("try")
    private static TeamAvatarRating mapRow(ResultSet rs) throws SQLException {
        TeamAvatarRating r = new TeamAvatarRating();
        r.setAvatarId(rs.getObject("avatar_id", UUID.class));
        r.setMatchCount(rs.getInt("match_count"));
        r.setSetCount(rs.getInt("set_count"));
        r.setPoints(rs.getInt("points"));
        r.setSetsWon(rs.getInt("sets_won"));
        r.setSetsLost(rs.getInt("sets_lost"));
        r.setBallsWon(rs.getInt("balls_won"));
        r.setBallsLost(rs.getInt("balls_lost"));
        r.setSetQuotient(rs.getDouble("set_quotient"));
        r.setBallQuotient(rs.getDouble("ball_quotient"));
        r.setWithoutAssessment(rs.getBoolean("is_without_assessment"));
        r.setUpdatedAt(
                rs.getObject("updated_at") != null
                        ? rs.getTimestamp("updated_at").toLocalDateTime()
                        : null);
        return r;
    }
}
