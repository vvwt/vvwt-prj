package de.vvwt.tm.tournament;

import de.vvwt.tm.domain.repo.TenantContext;
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
 * Tenant-scoped repository for {@link TeamAvatarRating} entities (DEC-21 public API surface).
 *
 * <p>Uses plain {@link JdbcTemplate} (not Spring Data JDBC CrudRepository) to avoid entity-mapping
 * conflicts with the parallel legacy {@code de.vvwt.tm.domain.TeamAvatarRating} during the
 * reconstruction-in-place phase (DEC-21/DEC-22). At the E21S13 atomic cutover, the legacy entity is
 * deleted.
 *
 * <h2>PK semantics</h2>
 *
 * <p>The {@code team_avatar_rating} table uses {@code avatar_id} as both PK and FK to {@code
 * team_avatar(id)}. There is no separate {@code id} column. All lookup/delete operations use {@code
 * avatar_id}.
 *
 * <p>Tenant scoping is enforced via the active {@link TenantContext} binding for all queries.
 *
 * <p>Inventory line 457 ({@code TeamAvatarRatingRepository}).
 *
 * @see TeamAvatarRating
 * @see TeamAvatar
 * @see <a href="DEC-21">DEC-21 — Spring Modulith, root package = public API surface</a>
 * @see <a href="DEC-22">DEC-22 — TDD reconstruction-in-place</a>
 * @see <a href="DEC-26">DEC-26 — DAO test governance (three rules)</a>
 * @see <a href="E21S04">E21S04 — Team aggregate reconstruction (inventory line 457)</a>
 */
@Repository("tmTeamAvatarRatingRepository")
public class TeamAvatarRatingRepository {

    private final JdbcTemplate jdbc;
    private final TenantContext tenantContext;

    private static final String INSERT_SQL =
            "INSERT INTO team_avatar_rating"
                    + " (avatar_id, tenant_id, match_count, set_count, points,"
                    + " sets_won, sets_lost, balls_won, balls_lost,"
                    + " set_quotient, ball_quotient, is_without_assessment, updated_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String UPDATE_SQL =
            "UPDATE team_avatar_rating SET"
                    + " match_count=?, set_count=?, points=?,"
                    + " sets_won=?, sets_lost=?, balls_won=?, balls_lost=?,"
                    + " set_quotient=?, ball_quotient=?, is_without_assessment=?,"
                    + " updated_at=?"
                    + " WHERE avatar_id=? AND tenant_id=?";

    private static final String SELECT_BY_AVATAR_ID =
            "SELECT * FROM team_avatar_rating WHERE avatar_id=? AND tenant_id=?";

    private static final String DELETE_BY_AVATAR_ID =
            "DELETE FROM team_avatar_rating WHERE avatar_id=? AND tenant_id=?";

    private static final String EXISTS_BY_AVATAR_ID =
            "SELECT COUNT(*) FROM team_avatar_rating WHERE avatar_id=? AND tenant_id=?";

    public TeamAvatarRatingRepository(JdbcTemplate jdbc, TenantContext tenantContext) {
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
    }

    /**
     * Persists a TeamAvatarRating. Inserts if new, updates if it exists. Tenant scoping enforced.
     *
     * @param rating the rating to save (avatarId must be set by caller)
     * @return the saved rating
     */
    public TeamAvatarRating save(TeamAvatarRating rating) {
        UUID currentTenantId = tenantContext.getTenantId();
        rating.setTenantId(currentTenantId);

        Integer count =
                jdbc.queryForObject(
                        EXISTS_BY_AVATAR_ID, Integer.class, rating.getAvatarId(), currentTenantId);
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
                    rating.getAvatarId(),
                    currentTenantId);
        } else {
            jdbc.update(
                    INSERT_SQL,
                    rating.getAvatarId(),
                    currentTenantId,
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

    /**
     * Returns the TeamAvatarRating for the given avatarId, scoped to the current tenant.
     *
     * @param avatarId the avatar UUID (PK)
     * @return Optional.of(rating) if found, Optional.empty() if not found or wrong tenant
     */
    public Optional<TeamAvatarRating> findByAvatarId(UUID avatarId) {
        UUID tenantId = tenantContext.getTenantId();
        List<TeamAvatarRating> results =
                jdbc.query(SELECT_BY_AVATAR_ID, ROW_MAPPER, avatarId, tenantId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Deletes the TeamAvatarRating for the given avatarId, scoped to the current tenant.
     *
     * @param avatarId the avatar UUID (PK)
     */
    public void deleteByAvatarId(UUID avatarId) {
        UUID tenantId = tenantContext.getTenantId();
        jdbc.update(DELETE_BY_AVATAR_ID, avatarId, tenantId);
    }

    // -------------------------------------------------------------------------
    // Row mapper
    // -------------------------------------------------------------------------

    private static final RowMapper<TeamAvatarRating> ROW_MAPPER = (rs, rowNum) -> mapRow(rs);

    @SuppressWarnings("try") // ResultSet is not AutoCloseable; suppress spurious try-resource lint
    private static TeamAvatarRating mapRow(ResultSet rs) throws SQLException {
        TeamAvatarRating r = new TeamAvatarRating();
        r.setAvatarId(rs.getObject("avatar_id", UUID.class));
        r.setTenantId(rs.getObject("tenant_id", UUID.class));
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
