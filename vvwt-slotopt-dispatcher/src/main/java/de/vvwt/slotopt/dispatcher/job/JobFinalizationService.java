// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.job;

import java.util.UUID;

/**
 * Service interface for job finalization operations.
 *
 * <p>Per DEC-35/DEC-58/DEC-72: this interface lives in the public {@code job} package. The
 * implementation ({@link de.vvwt.slotopt.dispatcher.job.internal.DefaultJobFinalizationService})
 * lives in {@code job.internal}. All consumers reference this interface exclusively (DEC-36).
 *
 * <p>Finalization is triggered when the last packet of a job reaches {@code RESULT_RECEIVED}. It
 * aggregates the global optimum across all packet results and transitions the {@link JobRecord}
 * from {@code DECOMPOSED} to {@code COMPLETED}. The aggregated result is written to the result
 * cache once at finalization, replacing the per-packet cache write removed from {@link
 * de.vvwt.slotopt.dispatcher.result.internal.DefaultSubmitResultService} per E60S03.
 *
 * <p>Story: E60S03; AC-GOV-FINALIZATION-CONFORMS-SPEC, AC-GOV-INTERFACE-MANDATE,
 * AC-ERR-FINALIZATION-DOES-NOT-BLOCK-RESULT-ACCEPT, AC-ERR-FINALIZATION-IDEMPOTENT,
 * AC-ERR-CACHE-WRITE-FAILURE-ABSORBED, AC-ERR-LAST-PACKET-DEFINITION; DEC-35, DEC-58, DEC-72, DEC-9
 */
public interface JobFinalizationService {

    /**
     * Checks whether all packets of the given job have reached {@code RESULT_RECEIVED}, and if so,
     * finalizes the job.
     *
     * <p>Finalization performs:
     *
     * <ol>
     *   <li>Load all {@link de.vvwt.slotopt.dispatcher.packet.PacketRecord}s for the job.
     *   <li>If any packet is NOT in {@code RESULT_RECEIVED} state → no-op (job not yet complete).
     *   <li>Load the {@link JobRecord} by {@code jobId}; if already {@code COMPLETED} → no-op
     *       (idempotent — AC-ERR-FINALIZATION-IDEMPOTENT).
     *   <li>Aggregate the global optimum from all retained {@link
     *       de.vvwt.slotopt.dispatcher.result.PacketResult}s: lowest {@code bestScore} wins; on
     *       tie, lowest {@code bestRank} wins (AC-GOV-FINALIZATION-CONFORMS-SPEC,
     *       AC-TEST-AGGREGATION-GLOBAL-OPTIMUM, AC-TEST-AGGREGATION-DETERMINISTIC).
     *   <li>Transition {@link JobRecord#setStatus(String)} from {@code DECOMPOSED} to {@code
     *       COMPLETED} and persist.
     *   <li>Write the aggregated optimum to the result cache keyed by structural fingerprint and V1
     *       game mode (AC-TEST-CACHE-HOLDS-AGGREGATED-OPTIMUM, AC-GOV-FINGERPRINT-STRUCTURAL).
     *       Cache write failure is absorbed — the job still reaches {@code COMPLETED}
     *       (AC-ERR-CACHE-WRITE-FAILURE-ABSORBED).
     * </ol>
     *
     * <p>Any exception thrown by finalization (including DB failures) MUST be caught and absorbed
     * by the caller ({@link de.vvwt.slotopt.dispatcher.result.internal.DefaultSubmitResultService})
     * so that the triggering packet's {@code RESULT_RECEIVED} transition and retention are not
     * rolled back (AC-ERR-FINALIZATION-DOES-NOT-BLOCK-RESULT-ACCEPT).
     *
     * @param jobId the UUID of the job to potentially finalize
     */
    void tryFinalizeJob(UUID jobId);
}
