package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.MatchOutcome;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/**
 * Spring Data JDBC delegate for {@link MatchOutcome} persistence.
 * Wired into {@link MatchOutcomeRepository} as the low-level CRUD provider.
 */
interface MatchOutcomeCrudRepository extends CrudRepository<MatchOutcome, UUID> {

    /**
     * Deletes the match outcome row for the given match ID (raw — no tenant filter).
     *
     * <p>Used by {@link MatchOutcomeRepository#deleteByMatchId} which applies the tenant
     * guard before delegating here.
     *
     * @param matchId the match whose outcome row to delete
     */
    @Modifying
    @Query("DELETE FROM match_outcome WHERE match_id = :matchId")
    void deleteByMatchIdRaw(@Param("matchId") UUID matchId);
}
