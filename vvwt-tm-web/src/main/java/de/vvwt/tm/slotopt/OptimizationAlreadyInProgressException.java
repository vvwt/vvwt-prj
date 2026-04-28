package de.vvwt.tm.slotopt;

import java.util.UUID;

/**
 * Thrown when an attempt is made to start a second slot-optimization for a tournament that already
 * has an active optimization in progress (E27S02, AC-PER-TOURNAMENT-ISOLATION, DEC-49 D-11).
 *
 * <p>Per-tournament registry semantics: at most one active optimization per tournament UUID. If an
 * optimization is already running for a given tournament, the second attempt throws this exception.
 *
 * @see SlotOptimizationJobRegistry
 * @see <a href="../../../../../../../../docs/governance/stories/E27S02.story.md">Story E27S02</a>
 */
public class OptimizationAlreadyInProgressException extends RuntimeException {

    /**
     * Constructs the exception for the given tournament.
     *
     * @param tournamentId the tournament UUID for which optimization is already in progress
     */
    public OptimizationAlreadyInProgressException(UUID tournamentId) {
        super(
                "Slot optimization is already in progress for tournament "
                        + tournamentId
                        + ". Cancel the existing optimization before starting a new one.");
    }
}
