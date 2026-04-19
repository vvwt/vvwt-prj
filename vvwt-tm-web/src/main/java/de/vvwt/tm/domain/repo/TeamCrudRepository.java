package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.Team;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JDBC delegate for {@link Team} persistence. Wired into {@link TeamRepository} as the
 * low-level CRUD provider.
 */
interface TeamCrudRepository extends CrudRepository<Team, UUID> {

    /**
     * Loads all teams in a given tournament without tenant filtering, ordered by team_number
     * ascending (AC1 — E05S05). Tenant filtering is applied by the {@link TeamRepository} wrapper.
     */
    @Query("SELECT * FROM team WHERE tournament_id = :tournamentId ORDER BY team_number ASC")
    List<Team> findByTournamentIdRaw(@Param("tournamentId") UUID tournamentId);

    /**
     * Counts how many team_avatar rows reference the given team (AC4 — E05S05). Used to prevent
     * deletion of teams that are already referenced in avatars.
     */
    @Query("SELECT COUNT(*) FROM team_avatar WHERE team_id = :teamId")
    int countAvatarsByTeamId(@Param("teamId") UUID teamId);

    /**
     * Counts teams in the tournament with the given team_number, excluding one team by ID. Used for
     * AC10 duplicate team_number detection on create and update.
     */
    @Query(
            "SELECT COUNT(*) FROM team WHERE tournament_id = :tournamentId "
                    + "AND team_number = :teamNumber AND id != :excludeId")
    int countByTournamentIdAndTeamNumberExcluding(
            @Param("tournamentId") UUID tournamentId,
            @Param("teamNumber") int teamNumber,
            @Param("excludeId") UUID excludeId);

    /**
     * Returns the maximum team_number in use for a tournament, or 0 if no teams exist. Used for
     * auto-assignment of team_number when not provided (AC2 — E05S05).
     */
    @Query("SELECT COALESCE(MAX(team_number), 0) FROM team WHERE tournament_id = :tournamentId")
    int findMaxTeamNumberByTournamentId(@Param("tournamentId") UUID tournamentId);
}
