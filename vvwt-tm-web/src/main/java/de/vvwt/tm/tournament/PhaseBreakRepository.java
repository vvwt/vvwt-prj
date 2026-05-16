package de.vvwt.tm.tournament;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Boundary-API tenant-scoped repository for {@link PhaseBreak} entities.
 *
 * <p>DEC-58 Clause A + DEC-72: every self-created Spring component must have a public interface in
 * the bounded-context root package.
 *
 * @see PhaseBreak
 * @since E57S01
 */
public interface PhaseBreakRepository {

    /**
     * Saves a {@link PhaseBreak}.
     *
     * @param phaseBreak the phase break to save
     * @return the saved phase break
     */
    PhaseBreak save(PhaseBreak phaseBreak);

    /**
     * Returns all phase breaks for the given phase.
     *
     * @param phaseId the phase to query
     * @return list of phase breaks; never null
     */
    List<PhaseBreak> findByPhaseId(UUID phaseId);

    /**
     * Returns the phase break at the given lap boundary within the given phase.
     *
     * @param phaseId the phase to query
     * @param afterLapNumber the lap boundary position
     * @return the phase break, or empty
     */
    Optional<PhaseBreak> findByPhaseIdAndAfterLapNumber(UUID phaseId, int afterLapNumber);

    /**
     * Returns the phase break with the given id.
     *
     * @param id the phase break UUID
     * @return Optional containing the phase break if found, empty otherwise
     */
    Optional<PhaseBreak> findById(UUID id);

    /**
     * Deletes the phase break with the given id.
     *
     * @param id the phase break UUID
     */
    void deleteById(UUID id);
}
