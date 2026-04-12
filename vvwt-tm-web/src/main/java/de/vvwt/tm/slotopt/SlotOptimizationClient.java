package de.vvwt.tm.slotopt;

import java.util.UUID;

/**
 * Client interface for the E04 slot-optimization service (AC8, D-29 step 2).
 *
 * <p>This interface decouples E03's phase-preparation service from the E04 Epic. E03 calls
 * {@link #optimize(UUID)} and expects that, on return, every match in the given phase has
 * non-null {@code lap_number} and {@code field_number} values written via the match repository.
 *
 * <h2>E04 integration</h2>
 * <p>When Epic E04 is delivered, it provides a real implementation of this interface (a Spring
 * {@code @Primary @Service} or similar). The {@link FallbackSlotOptimizationClient} is
 * auto-disabled at that point via {@code @ConditionalOnMissingBean(SlotOptimizationClient.class)}.
 *
 * <h2>V1 fallback</h2>
 * <p>For E03 delivery in isolation, the {@link FallbackSlotOptimizationClient} provides a
 * sequential lap/field assignment that produces a valid (though non-optimal) schedule.
 *
 * @see FallbackSlotOptimizationClient
 * @see <a href="../../../../../../../../.gaai/project/contexts/artefacts/stories/E03S12.story.md">Story E03S12</a>
 */
public interface SlotOptimizationClient {

    /**
     * Assigns {@code lap_number} and {@code field_number} to every match in the given phase.
     *
     * <p>After this method returns successfully, every match returned by
     * {@code MatchRepository.findByPhaseId(phaseId)} must have non-null
     * {@code lapNumber} and {@code fieldNumber}.
     *
     * <p>The method must be idempotent: calling it a second time on the same phase must
     * overwrite the previous slot assignments with valid new ones. The end state must always
     * satisfy the non-null coordinate invariant.
     *
     * @param phaseId the phase whose matches should receive slot assignments; must not be null
     * @throws IllegalArgumentException if {@code phaseId} is null or the phase does not exist
     * @throws IllegalStateException    if no matches exist for the phase (nothing to optimize)
     */
    void optimize(UUID phaseId);
}
