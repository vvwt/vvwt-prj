package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.TeamAvatar;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JDBC delegate for {@link TeamAvatar} persistence.
 * Wired into {@link TeamAvatarRepository} as the low-level CRUD provider.
 */
interface TeamAvatarCrudRepository extends CrudRepository<TeamAvatar, UUID> {
    @Query("SELECT * FROM team_avatar WHERE phase_id = :phaseId")
    List<TeamAvatar> findByPhaseIdRaw(@Param("phaseId") UUID phaseId);
}
