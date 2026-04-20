package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.PhaseBreak;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JDBC delegate for {@link PhaseBreak} persistence (implementation — inventory line
 * 295).
 *
 * <p>Lives at {@code de.vvwt.tm.tournament.internal} per DEC-21 internal-package discipline. Wired
 * into {@link de.vvwt.tm.tournament.PhaseBreakRepository} as the low-level CRUD provider.
 *
 * <p><b>Activation note:</b> Not active during E21 reconstruction-in-place phase — dual entity
 * conflict same as {@link PhaseCrudRepository}. Activated at E21S13 cutover.
 *
 * @see de.vvwt.tm.tournament.PhaseBreakRepository
 * @see <a href="DEC-21">DEC-21 — Spring Modulith internal-package discipline</a>
 * @see <a href="E21S03">E21S03 — Phase cluster reconstruction (inventory line 295)</a>
 */
public interface PhaseBreakCrudRepository extends CrudRepository<PhaseBreak, UUID> {

    /**
     * Returns all phase breaks for a given phase (unscoped — tenant filtering applied by {@link
     * de.vvwt.tm.tournament.PhaseBreakRepository#findByPhaseId(UUID)}).
     *
     * @param phaseId the phase to query
     * @return all phase breaks for the given phase, unscoped by tenant
     */
    @Query("SELECT * FROM phase_breaks WHERE phase_id = :phaseId")
    List<PhaseBreak> findByPhaseIdRaw(@Param("phaseId") UUID phaseId);

    /**
     * Returns the phase break at a specific lap boundary within a phase, if one exists (unscoped —
     * tenant filtering applied by {@link
     * de.vvwt.tm.tournament.PhaseBreakRepository#findByPhaseIdAndAfterLapNumber(UUID, int)}).
     *
     * @param phaseId the phase to query
     * @param afterLapNumber the lap boundary position
     * @return the phase break at the given position, or {@link Optional#empty()} if absent
     */
    @Query(
            "SELECT * FROM phase_breaks WHERE phase_id = :phaseId"
                    + " AND after_lap_number = :afterLapNumber")
    Optional<PhaseBreak> findByPhaseIdAndLapNumberRaw(
            @Param("phaseId") UUID phaseId, @Param("afterLapNumber") int afterLapNumber);
}
