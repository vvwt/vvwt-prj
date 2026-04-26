package de.vvwt.slotopt.dispatcher.job;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for the {@code POST /api/submit-job} endpoint.
 *
 * <p>Field names match the spec verbatim (per AC-SUBMIT-JOB-DTOs):
 *
 * <ul>
 *   <li>{@code jobId} — the UUID assigned to the accepted job (also set on cache-hit responses)
 *   <li>{@code submittedAt} — the instant the job was persisted (null on cache-hit short-circuit)
 *   <li>{@code cacheHit} — {@code true} if the response was served from the results cache
 *       (AC-CACHE-READ-SHORT-CIRCUIT, E37S10 retrofit); {@code false} for normal job submission
 * </ul>
 *
 * <p>Story: E37S07; AC-SUBMIT-JOB-DTOs; E37S10: AC-CACHE-READ-SHORT-CIRCUIT
 */
public record SubmitJobResponse(UUID jobId, Instant submittedAt, boolean cacheHit) {

    /**
     * Convenience constructor for normal (non-cache-hit) job submission responses.
     *
     * @param jobId the assigned job UUID
     * @param submittedAt the persistence timestamp
     */
    public SubmitJobResponse(UUID jobId, Instant submittedAt) {
        this(jobId, submittedAt, false);
    }
}
