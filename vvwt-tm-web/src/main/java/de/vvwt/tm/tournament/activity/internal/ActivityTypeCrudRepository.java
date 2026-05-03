package de.vvwt.tm.tournament.activity.internal;

import de.vvwt.tm.tournament.activity.ActivityType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JDBC delegate for {@link ActivityType} persistence. Wired into {@link
 * DefaultActivityTypeRepository} as the low-level CRUD provider.
 *
 * <p>This is an {@code internal} type — it MUST NOT be accessed directly by any code outside {@code
 * de.vvwt.tm.tournament.activity.internal.*}. Consumers use the public {@link
 * de.vvwt.tm.tournament.activity.ActivityTypeRepository} interface.
 *
 * <p><b>E45S01 relocation note:</b> Relocated from {@code
 * de.vvwt.tm.domain.repo.ActivityTypeCrudRepository}.
 */
interface ActivityTypeCrudRepository extends CrudRepository<ActivityType, UUID> {

    /**
     * Returns all activity types belonging to the given tournament (unscoped — tenant filtering is
     * applied in {@link DefaultActivityTypeRepository#findByTournamentId(UUID)}).
     *
     * @param tournamentId the tournament to query
     * @return all activity types for the given tournament, unscoped by tenant
     */
    @Query("SELECT * FROM activity_types WHERE tournament_id = :tournamentId")
    List<ActivityType> findByTournamentIdRaw(@Param("tournamentId") UUID tournamentId);
}
