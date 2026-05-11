package de.vvwt.tm.phaselifecycle;

import java.util.UUID;

/**
 * Value object representing a pending entry in the {@code phase_lifecycle_job} DB-durable queue
 * (DEC-64 D-4).
 *
 * <p>This record carries the minimal fields required to enqueue a job. The full schema (including
 * {@code status}, {@code claimed_by}, timestamps) lives in the DB table; this record represents the
 * enqueue-time contract only.
 *
 * <p>Not a Spring bean — a plain value object passed to {@link
 * PhaseLifecycleJobRepository#enqueueJob(PhaseLifecycleJob)}.
 *
 * @param tournamentId the tournament this job belongs to
 * @param phaseId the phase to be processed by MatchGen → L1+L2 → SlotOpt
 * @param gameMode the {@code DraftSection.gameMode} value (e.g., "standard", "siegerehrung"); used
 *     by the orchestrator to select the correct MatchGenerator
 * @param sequence the phase sequence number within the tournament; determines FIFO drain order
 * @since E55S01
 */
public record PhaseLifecycleJob(UUID tournamentId, UUID phaseId, String gameMode, int sequence) {}
