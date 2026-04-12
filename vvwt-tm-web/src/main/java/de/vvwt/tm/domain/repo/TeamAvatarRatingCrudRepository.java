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
     * Returns all ratings for avatars in the given phase (unfiltered — caller applies tenant check).
     *
     * <p>Used by {@link TeamAvatarRatingRepository#findByPhaseId(UUID)} and the
     * round-end snapshot service (E03S13) to load standings for snapshot payload generation.
     *
     * @param phaseId the phase whose avatar ratings are to be loaded
     * @return all TeamAvatarRating rows whose avatar belongs to the given phase
     */
    @Query("SELECT tar.* FROM team_avatar_rating tar "
         + "INNER JOIN team_avatar ta ON ta.id = tar.avatar_id "
         + "WHERE ta.phase_id = :phaseId")
    List<TeamAvatarRating> findByPhaseIdRaw(@Param("phaseId") UUID phaseId);
}
