package de.vvwt.tm.slotopt;

/**
 * Result applicator: maps a permutation rank from the slot-optimization compute kernel back to
 * {@code (lapNumber, fieldNumber)} coordinates on each match entity.
 *
 * <p>DEC-58 Clause A + DEC-72: every self-created Spring component must have a public interface in
 * the bounded-context root package.
 *
 * @since E57S01
 */
public interface SlotResultApplicator {

    /**
     * Applies a lap-permutation rank to the matches in the given mapping result, writing {@code
     * lapNumber} and {@code fieldNumber} to every match.
     *
     * @param rank the lap-permutation rank from the optimizer; {@code 0} = identity (L2 baseline)
     * @param fieldCount the number of courts/fields per lap; must be {@code >= 1}
     * @param mapping the forward-mapping result from {@link PhaseToRawPhaseDefMapper}
     * @throws IllegalArgumentException if {@code mapping} is {@code null} or {@code fieldCount < 1}
     * @throws IllegalArgumentException if {@code rank >= lapCount!}
     */
    void applyResult(long rank, int fieldCount, MappingResult mapping);
}
