package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.Phase;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JDBC delegate for {@link Phase} persistence.
 * Wired into {@link PhaseRepository} as the low-level CRUD provider.
 */
interface PhaseCrudRepository extends CrudRepository<Phase, UUID> {

    /**
     * Returns all phases belonging to the given tournament (unscoped — tenant filtering
     * is applied in {@link PhaseRepository#findByTournamentId(UUID)}).
     *
     * @param tournamentId the tournament to query
     * @return all phases for the given tournament, unscoped by tenant
     */
    @Query("SELECT * FROM phase WHERE tournament_id = :tournamentId")
    List<Phase> findByTournamentIdRaw(@Param("tournamentId") UUID tournamentId);
}
