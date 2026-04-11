package de.vvwt.dispatcher.result;

import java.util.UUID;

/**
 * Response body for {@code GET /jobs/{jobId}} (E01S08 AC12).
 *
 * <p>Read-only, no auth required (Phase 1 — public read per AC12).
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code jobId} — UUID of the job</li>
 *   <li>{@code status} — current job status (queued, decomposing, ready, done, failed)</li>
 *   <li>{@code packetsTotal} — total packets created for this job (null if not yet decomposed)</li>
 *   <li>{@code packetsCompleted} — packets in {@code "done"} state</li>
 *   <li>{@code packetsAssigned} — packets in {@code "assigned"} state</li>
 *   <li>{@code packetsPending} — packets in {@code "pending"} state</li>
 *   <li>{@code packetsFailed} — packets in {@code "failed"} state</li>
 *   <li>{@code bestSoFar} — the best result seen across completed packets so far; null if none done</li>
 *   <li>{@code finalResult} — the finalized result; non-null only when job is {@code "done"}</li>
 * </ul>
 *
 * <p>See Story E01S08 AC12.
 */
public record JobStatusResponse(
        UUID jobId,
        String status,
        Integer packetsTotal,
        long packetsCompleted,
        long packetsAssigned,
        long packetsPending,
        long packetsFailed,
        ResultSummary bestSoFar,
        ResultSummary finalResult) {

    /**
     * A (rank, score) pair for {@code bestSoFar} and {@code finalResult}.
     */
    public record ResultSummary(long rank, double score) {}
}
