package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.TeamAvatarRating;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JDBC delegate for {@link TeamAvatarRating} persistence.
 * Wired into {@link TeamAvatarRatingRepository} as the low-level CRUD provider.
 */
interface TeamAvatarRatingCrudRepository extends CrudRepository<TeamAvatarRating, UUID> {

    /**
     * Returns all ratings whose avatar belongs to the given phase (raw — no tenant filter).
     *
     * <p>Joins {@code team_avatar_rating} with {@code team_avatar} on {@code avatar_id = id}
     * to filter by {@code team_avatar.phase_id}. Used by
     * {@link TeamAvatarRatingRepository#findByPhaseIdRaw(UUID)}.
     *
     * @param phaseId the phase to query
     * @return list of ratings for all avatars in the phase; never {@code null}
     */
    @Query("SELECT tar.* FROM team_avatar_rating tar "
         + "JOIN team_avatar ta ON tar.avatar_id = ta.id "
         + "WHERE ta.phase_id = :phaseId")
    List<TeamAvatarRating> findByPhaseIdRaw(@Param("phaseId") UUID phaseId);
}
