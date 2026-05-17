// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.tm.web.slotopt;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * HTTP response body for {@code GET /api/slotopt/tournaments/{tournamentId}/status} (E27S02,
 * AC-CANCEL-CONTROLLER-AUTHORED; E51S07 AC-IMPL-STATUS-ENDPOINT-EXTENSION).
 *
 * <p>Fields:
 *
 * <ul>
 *   <li>{@link #state} — {@code "running"}, {@code "idle"}, or {@code "cancelled"}
 *   <li>{@link #startedAt} — ISO-8601 timestamp of job start, {@code null} if idle
 *   <li>{@link #bestSoFarVarietyScore} — variety score of best permutation found so far, {@code
 *       null} if no permutation evaluated yet or idle
 *   <li>{@link #lastJobState} — {@code phase.last_job_state} DB column value for the FIFO-head
 *       phase of the tournament; {@code null} when no phase is in the queue or the column is null
 *       (E51S07 additive extension — backward-compatible; {@code @JsonInclude(NON_NULL)} suppresses
 *       the field in responses where it is null)
 * </ul>
 *
 * <h2>Backward compatibility (E51S07)</h2>
 *
 * <p>The {@code lastJobState} field is additive. Existing consumers that do not read {@code
 * lastJobState} are unaffected. The field is serialized only when non-null per
 * {@code @JsonInclude(NON_NULL)}.
 *
 * @see SlotOptimizationCancelController
 * @see <a href="../../../../../../../../../docs/governance/stories/E27S02.story.md">Story
 *     E27S02</a>
 * @see <a href="../../../../../../../../../docs/governance/decisions/DEC-55.md">DEC-55 D-9</a>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SlotOptimizationStatusResponse(
        String state, Instant startedAt, Double bestSoFarVarietyScore, String lastJobState) {

    // -------------------------------------------------------------------------
    // E27S02 backward-compatible factories (preserve existing callers)
    // -------------------------------------------------------------------------

    /** Factory: idle state (no active optimization). lastJobState null. */
    public static SlotOptimizationStatusResponse idle() {
        return new SlotOptimizationStatusResponse("idle", null, null, null);
    }

    /** Factory: running state with start time and optional best-so-far score. lastJobState null. */
    public static SlotOptimizationStatusResponse running(Instant startedAt, Double bestSoFar) {
        return new SlotOptimizationStatusResponse("running", startedAt, bestSoFar, null);
    }

    /** Factory: cancelled state (job completed via cancel). lastJobState null. */
    public static SlotOptimizationStatusResponse cancelled(Instant startedAt, Double bestSoFar) {
        return new SlotOptimizationStatusResponse("cancelled", startedAt, bestSoFar, null);
    }

    // -------------------------------------------------------------------------
    // E51S07 extended factories (with lastJobState — AC-IMPL-STATUS-ENDPOINT-EXTENSION)
    // -------------------------------------------------------------------------

    /**
     * Factory: response carrying an explicit {@code lastJobState} value from {@code
     * phase.last_job_state} (E51S07 AC-IMPL-STATUS-ENDPOINT-EXTENSION).
     *
     * @param lastJobState the {@code phase.last_job_state} value; may be {@code null}
     */
    public static SlotOptimizationStatusResponse withLastJobState(String lastJobState) {
        return new SlotOptimizationStatusResponse("idle", null, null, lastJobState);
    }

    /**
     * Factory: running state carrying an explicit {@code lastJobState} value (E51S07).
     *
     * @param startedAt job start time
     * @param bestSoFar best-so-far score; may be {@code null}
     * @param lastJobState the {@code phase.last_job_state} value; may be {@code null}
     */
    public static SlotOptimizationStatusResponse runningWithJobState(
            Instant startedAt, Double bestSoFar, String lastJobState) {
        return new SlotOptimizationStatusResponse("running", startedAt, bestSoFar, lastJobState);
    }

    /**
     * Factory: cancelled state carrying an explicit {@code lastJobState} value (E51S07).
     *
     * @param startedAt job start time
     * @param bestSoFar best-so-far score; may be {@code null}
     * @param lastJobState the {@code phase.last_job_state} value; may be {@code null}
     */
    public static SlotOptimizationStatusResponse cancelledWithJobState(
            Instant startedAt, Double bestSoFar, String lastJobState) {
        return new SlotOptimizationStatusResponse("cancelled", startedAt, bestSoFar, lastJobState);
    }
}
