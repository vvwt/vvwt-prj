package de.vvwt.tm.slotopt;

import java.util.UUID;

/**
 * Forward mapper: converts TM domain objects for a given phase into a {@link MappingResult} needed
 * by the slot-optimization compute kernel.
 *
 * <p>DEC-58 Clause A + DEC-72: every self-created Spring component must have a public interface in
 * the bounded-context root package.
 *
 * @since E57S01
 */
public interface PhaseToRawPhaseDefMapper {

    /**
     * Maps all domain objects for the given phase to a {@link MappingResult}.
     *
     * @param phaseId the UUID of the phase to map
     * @return the mapping result containing the RawPhaseDef, canonical form, N, and match-to-row
     *     index
     * @throws IllegalArgumentException if {@code phaseId} is {@code null}
     * @throws IllegalStateException if no matches exist, no avatars exist, or a match references an
     *     unknown avatar
     */
    MappingResult map(UUID phaseId);

    /**
     * Returns the configured field count for slot assignment.
     *
     * @return the number of courts (fields)
     */
    int getFieldCount();
}
