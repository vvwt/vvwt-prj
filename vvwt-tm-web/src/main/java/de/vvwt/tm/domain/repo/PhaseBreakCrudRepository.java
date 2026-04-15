package de.vvwt.tm.domain.repo;

import de.vvwt.tm.domain.PhaseBreak;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JDBC delegate for {@link PhaseBreak} persistence.
 * Wired into {@link PhaseBreakRepository} as the low-level CRUD provider.
 *
 * <p>All methods here are "raw" — they do not apply the tenant filter.
 * {@link PhaseBreakRepository} is responsible for all tenant-scoped operations.
 */
interface PhaseBreakCrudRepository extends CrudRepository<PhaseBreak, UUID> {

    /**
     * Returns all phase breaks for a given phase (unscoped — tenant filtering applied by
     * {@link PhaseBreakRepository#findByPhaseId(UUID)}).
     *
     * @param phaseId the phase to query
     * @return all phase breaks for the given phase, unscoped by tenant
     */
    @Query("SELECT * FROM phase_breaks WHERE phase_id = :phaseId")
    List<PhaseBreak> findByPhaseIdRaw(@Param("phaseId") UUID phaseId);

    /**
     * Returns the phase break at a specific lap boundary within a phase, if one exists
     * (unscoped — tenant filtering applied by
     * {@link PhaseBreakRepository#findByPhaseIdAndAfterLapNumber(UUID, int)}).
     *
     * @param phaseId        the phase to query
     * @param afterLapNumber the lap boundary position
     * @return the phase break at the given position, or {@link Optional#empty()} if absent
     */
    @Query("SELECT * FROM phase_breaks WHERE phase_id = :phaseId AND after_lap_number = :afterLapNumber")
    Optional<PhaseBreak> findByPhaseIdAndLapNumberRaw(@Param("phaseId") UUID phaseId,
                                                       @Param("afterLapNumber") int afterLapNumber);
}
