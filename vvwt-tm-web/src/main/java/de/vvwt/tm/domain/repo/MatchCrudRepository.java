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
}
