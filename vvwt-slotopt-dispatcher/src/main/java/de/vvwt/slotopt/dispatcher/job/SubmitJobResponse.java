package de.vvwt.slotopt.dispatcher.job;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for the {@code POST /api/submit-job} endpoint.
 *
 * <p>Field names match the spec verbatim (per AC-SUBMIT-JOB-DTOs):
 *
 * <ul>
 *   <li>{@code jobId} — the UUID assigned to the accepted job
 *   <li>{@code submittedAt} — the instant the job was persisted
 * </ul>
 *
 * <p>Story: E37S07; AC-SUBMIT-JOB-DTOs
 */
public record SubmitJobResponse(UUID jobId, Instant submittedAt) {}
