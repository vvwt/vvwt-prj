// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.job;

/**
 * Service interface for job submission operations.
 *
 * <p>Per DEC-35: this interface lives in the public {@code job} package. The implementation ({@link
 * de.vvwt.slotopt.dispatcher.job.internal.DefaultJobService}) lives in {@code job.internal}.
 *
 * <p>All consumers (controllers, tests) type their dependency as {@code JobService}, never as the
 * implementation class (DEC-36).
 *
 * <p>Story: E37S07; AC-JOB-SERVICE; DEC-35, DEC-36
 */
public interface JobService {

    /**
     * Submits an optimization job to the dispatcher.
     *
     * <p>Behavior per AC-JOB-SERVICE:
     *
     * <ol>
     *   <li>Validates that {@code request.phase()} is not null and that {@code
     *       request.phase().rowCount()} is in [1, 15] (Phase 1 N-cap per spec section (b)).
     *       Violations → {@link IllegalArgumentException} (→ HTTP 400).
     *   <li>Computes the structural fingerprint via {@link
     *       de.vvwt.slotopt.worker.types.StructuralFingerprint#transform(de.vvwt.slotopt.worker.types.RawPhaseDef)}.
     *   <li>Serializes the {@link de.vvwt.slotopt.worker.types.JobDef} to JSON ({@code
     *       jobDefJson}).
     *   <li>Generates a job UUID.
     *   <li>Persists a {@link JobRecord} with status {@code RECEIVED}.
     *   <li>Returns a {@link SubmitJobResponse} with the assigned job UUID and submission
     *       timestamp.
     * </ol>
     *
     * @param request the job submission request; must not be {@code null}
     * @return the response containing the assigned job ID and submission timestamp
     * @throws IllegalArgumentException if the phase is null or {@code rowCount} is out of range [1,
     *     15]
     */
    SubmitJobResponse submitJob(SubmitJobRequest request);
}
