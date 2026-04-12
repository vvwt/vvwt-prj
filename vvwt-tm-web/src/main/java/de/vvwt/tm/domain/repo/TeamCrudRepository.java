package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.Team;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JDBC delegate for {@link Team} persistence.
 * Wired into {@link TeamRepository} as the low-level CRUD provider.
 */
interface TeamCrudRepository extends CrudRepository<Team, UUID> {

    /**
     * Loads all teams in a given tournament without tenant filtering.
     * Tenant filtering is applied by the {@link TeamRepository} wrapper.
     */
    @Query("SELECT * FROM team WHERE tournament_id = :tournamentId")
    List<Team> findByTournamentIdRaw(@Param("tournamentId") UUID tournamentId);
}
