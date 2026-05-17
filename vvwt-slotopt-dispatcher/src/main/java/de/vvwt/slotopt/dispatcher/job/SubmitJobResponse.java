// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.job;

import de.vvwt.slotopt.dispatcher.result.OptimumResult;
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
 *   <li>{@code cachedResult} — present only on a cache-hit response; carries the cached global
 *       optimum ({@code bestRank} / {@code bestScore}) inline so the submitter can retrieve the
 *       cached result without a separate job-status query (AC-TEST-CACHE-HIT-RESPONSE-USABLE,
 *       E60S04). Null for normal (non-cache-hit) submissions. Null if the cached payload is
 *       malformed (AC-ERR-CACHE-HIT-RESPONSE-CONSISTENT — the submitter is never given a broken
 *       reference). DEC-9: carries only structural optimization data, no team UUIDs or names.
 * </ul>
 *
 * <p>Story: E37S07; AC-SUBMIT-JOB-DTOs; E37S10: AC-CACHE-READ-SHORT-CIRCUIT; E60S04:
 * AC-TEST-CACHE-HIT-RESPONSE-USABLE, AC-ERR-CACHE-HIT-RESPONSE-CONSISTENT, DEC-9
 */
public record SubmitJobResponse(
        UUID jobId, Instant submittedAt, boolean cacheHit, OptimumResult cachedResult) {

    /**
     * Convenience constructor for normal (non-cache-hit) job submission responses.
     *
     * @param jobId the assigned job UUID
     * @param submittedAt the persistence timestamp
     */
    public SubmitJobResponse(UUID jobId, Instant submittedAt) {
        this(jobId, submittedAt, false, null);
    }
}
