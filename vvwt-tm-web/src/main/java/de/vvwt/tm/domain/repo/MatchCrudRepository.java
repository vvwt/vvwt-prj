package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.Match;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JDBC delegate for {@link Match} persistence.
 * Wired into {@link MatchRepository} as the low-level CRUD provider.
 */
interface MatchCrudRepository extends CrudRepository<Match, UUID> {
    @Query("SELECT * FROM \"match\" WHERE phase_id = :phaseId")
    List<Match> findByPhaseIdRaw(@Param("phaseId") UUID phaseId);

    /**
     * Returns all terminal matches for a given avatar in a given phase.
     *
     * <p>Used by {@link MatchRepository#findTerminalByPhaseIdAndAvatarId} for cascade
     * steps 7–8 (E03S11, AC9/AC10). The OR clause is parenthesized to prevent SQL
     * precedence bugs.
     *
     * <p>Terminal states: 50 (FINISHED_STANDOFF), 51 (FINISHED_WINNER1), 52 (FINISHED_WINNER2).
     *
     * @param phaseId  the phase to query
     * @param avatarId the team avatar to find matches for
     * @return list of terminal matches for the avatar in the phase
     */
    @Query("SELECT * FROM \"match\" WHERE phase_id = :phaseId "
         + "AND (member_avatar_1_id = :avatarId OR member_avatar_2_id = :avatarId) "
         + "AND state IN (50, 51, 52)")
    List<Match> findTerminalByPhaseIdAndAvatarIdRaw(@Param("phaseId") UUID phaseId,
                                                    @Param("avatarId") UUID avatarId);
}
