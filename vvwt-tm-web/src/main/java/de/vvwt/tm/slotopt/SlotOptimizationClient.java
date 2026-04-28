package de.vvwt.tm.slotopt;

import java.util.UUID;

/**
 * Client interface for the E04 slot-optimization service (AC8, D-29 step 2).
 *
 * <p>This interface decouples E03's phase-preparation service from the E04 Epic. E03 calls {@link
 * #optimize(UUID)} and expects that, on return, every match in the given phase has non-null {@code
 * lap_number} and {@code field_number} values written via the match repository.
 *
 * <h2>E27 integration (current)</h2>
 *
 * <p>As of E27S01, {@link de.vvwt.tm.slotopt.internal.RoutingSlotOptimizationClient} is the
 * {@code @Primary @Service} implementation. It dispatches to one of three routing legs per DEC-49.
 * {@link FallbackSlotOptimizationClient} is disabled (its {@code @ConditionalOnMissingBean}
 * evaluates to false because two {@link SlotOptimizationClient} beans exist: Routing + Direct).
 *
 * <h2>E04 integration (historical)</h2>
 *
 * <p>Epic E04 delivered {@link DirectSlotOptimizationClient} as the first real implementation. E27
 * builds the routing layer on top, preserving Direct as a non-Primary delegate for Leg 1.
 *
 * @see de.vvwt.tm.slotopt.internal.RoutingSlotOptimizationClient
 * @see DirectSlotOptimizationClient
 * @see <a href="../../../../../../../../docs/governance/stories/E03S12.story.md">Story E03S12</a>
 */
public interface SlotOptimizationClient {

    /**
     * Assigns {@code lap_number} and {@code field_number} to every match in the given phase.
     *
     * <p>After this method returns successfully, every match returned by {@code
     * MatchRepository.findByPhaseId(phaseId)} must have non-null {@code lapNumber} and {@code
     * fieldNumber}.
     *
     * <p>The method must be idempotent: calling it a second time on the same phase must overwrite
     * the previous slot assignments with valid new ones. The end state must always satisfy the
     * non-null coordinate invariant.
     *
     * @param phaseId the phase whose matches should receive slot assignments; must not be null
     * @throws IllegalArgumentException if {@code phaseId} is null or the phase does not exist
     * @throws IllegalStateException if no matches exist for the phase (nothing to optimize)
     */
    void optimize(UUID phaseId);
}
