package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.Team;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

/**
 * Spring Data JDBC CrudRepository delegate for {@link Team} (implementation surface).
 *
 * <p>Not a public API; accessed exclusively through {@link de.vvwt.tm.tournament.TeamRepository}.
 * Lives in {@code tournament.internal} per DEC-21 §Module layout.
 *
 * <p>Inventory line 306: {@code de.vvwt.tm.domain.repo.TeamCrudRepository}.
 *
 * @see de.vvwt.tm.tournament.TeamRepository
 * @see <a href="DEC-21">DEC-21 — Spring Modulith internal-package discipline</a>
 * @see <a href="E21S04">E21S04 — Team aggregate reconstruction</a>
 */
public interface TeamCrudRepository extends CrudRepository<Team, UUID> {

    /**
     * Returns all Team rows matching the given tournament_id (no tenant filter — caller applies
     * tenant scope).
     *
     * @param tournamentId the tournament to filter by
     * @return raw list of teams in this tournament across all tenants
     */
    @Query("SELECT * FROM team WHERE tournament_id = :tournamentId ORDER BY team_number ASC")
    List<Team> findByTournamentIdRaw(UUID tournamentId);

    /**
     * Returns the maximum team_number in the given tournament, or 0 if no teams exist.
     *
     * @param tournamentId the tournament to query
     * @return max team_number, or 0 if absent
     */
    @Query(
            "SELECT COALESCE(MAX(team_number), 0) FROM team WHERE tournament_id ="
                    + " :tournamentId")
    int findMaxTeamNumberByTournamentId(UUID tournamentId);

    /**
     * Returns the count of TeamAvatar rows referencing the given team.
     *
     * @param teamId the team UUID
     * @return count of avatar references
     */
    @Query("SELECT COUNT(*) FROM team_avatar WHERE team_id = :teamId")
    int countAvatarsByTeamId(UUID teamId);

    /**
     * Returns whether a team with the given team_number exists in the tournament, excluding one
     * specific team (used for update uniqueness check).
     *
     * @param tournamentId the tournament scope
     * @param teamNumber the team number to check
     * @param excludeId the team UUID to exclude from the check (pass a zero UUID for new teams)
     * @return count of teams with that number (0 or 1 in practice)
     */
    @Query(
            "SELECT COUNT(*) FROM team WHERE tournament_id = :tournamentId"
                    + " AND team_number = :teamNumber AND id <> :excludeId")
    int countByTournamentIdAndTeamNumberExcluding(
            UUID tournamentId, int teamNumber, UUID excludeId);
}
