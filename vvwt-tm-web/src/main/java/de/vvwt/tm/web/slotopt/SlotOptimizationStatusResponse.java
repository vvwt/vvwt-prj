package de.vvwt.tm.web.slotopt;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * HTTP response body for {@code GET /api/slotopt/tournaments/{tournamentId}/status} (E27S02,
 * AC-CANCEL-CONTROLLER-AUTHORED).
 *
 * <p>Fields:
 *
 * <ul>
 *   <li>{@link #state} — {@code "running"}, {@code "idle"}, or {@code "cancelled"}
 *   <li>{@link #startedAt} — ISO-8601 timestamp of job start, {@code null} if idle
 *   <li>{@link #bestSoFarVarietyScore} — variety score of best permutation found so far, {@code
 *       null} if no permutation evaluated yet or idle
 * </ul>
 *
 * @see SlotOptimizationCancelController
 * @see <a href="../../../../../../../../../docs/governance/stories/E27S02.story.md">Story
 *     E27S02</a>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SlotOptimizationStatusResponse(
        String state, Instant startedAt, Double bestSoFarVarietyScore) {

    /** Factory: idle state (no active optimization). */
    public static SlotOptimizationStatusResponse idle() {
        return new SlotOptimizationStatusResponse("idle", null, null);
    }

    /** Factory: running state with start time and optional best-so-far score. */
    public static SlotOptimizationStatusResponse running(Instant startedAt, Double bestSoFar) {
        return new SlotOptimizationStatusResponse("running", startedAt, bestSoFar);
    }

    /** Factory: cancelled state (job completed via cancel). */
    public static SlotOptimizationStatusResponse cancelled(Instant startedAt, Double bestSoFar) {
        return new SlotOptimizationStatusResponse("cancelled", startedAt, bestSoFar);
    }
}
