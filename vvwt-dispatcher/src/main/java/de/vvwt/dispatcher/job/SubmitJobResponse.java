package de.vvwt.dispatcher.job;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * HTTP response body for {@code POST /submit-job} (AC5, AC9, AC10 of E01S06).
 *
 * <p>{@code cachedResult} is present only when {@code status == "cached"} (AC9).
 * Jackson excludes null fields via {@link JsonInclude.Include#NON_NULL}.
 *
 * @param jobId        UUID of the job; present for both queued and cached responses
 * @param status       {@code "queued"} (AC10) or {@code "cached"} (AC9)
 * @param cachedResult present only on cache hit (status = "cached")
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubmitJobResponse(UUID jobId, String status, CachedResultSummary cachedResult) {

    /**
     * Summary of the cached result returned on a cache hit (AC9).
     *
     * @param bestRank   best permutation rank found by workers
     * @param bestScore  variety score for {@code bestRank}
     * @param computedAt timestamp when the result was finalized
     */
    public record CachedResultSummary(long bestRank, double bestScore, Instant computedAt) {
    }
}
