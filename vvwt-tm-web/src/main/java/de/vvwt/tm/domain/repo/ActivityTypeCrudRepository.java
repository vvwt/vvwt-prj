package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.ActivityType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JDBC delegate for {@link ActivityType} persistence. Wired into {@link
 * ActivityTypeRepository} as the low-level CRUD provider.
 */
interface ActivityTypeCrudRepository extends CrudRepository<ActivityType, UUID> {

    /**
     * Returns all activity types belonging to the given tournament (unscoped — tenant filtering is
     * applied in {@link ActivityTypeRepository#findByTournamentId(UUID)}).
     *
     * @param tournamentId the tournament to query
     * @return all activity types for the given tournament, unscoped by tenant
     */
    @Query("SELECT * FROM activity_types WHERE tournament_id = :tournamentId")
    List<ActivityType> findByTournamentIdRaw(@Param("tournamentId") UUID tournamentId);
}
