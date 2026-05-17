// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.job.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.vvwt.slotopt.dispatcher.cache.ResultsCacheService;
import de.vvwt.slotopt.dispatcher.job.JobFinalizationService;
import de.vvwt.slotopt.dispatcher.job.JobRecord;
import de.vvwt.slotopt.dispatcher.job.JobRepository;
import de.vvwt.slotopt.dispatcher.packet.PacketRecord;
import de.vvwt.slotopt.dispatcher.packet.PacketRepository;
import de.vvwt.slotopt.dispatcher.result.PacketResult;
import de.vvwt.slotopt.dispatcher.result.PacketResultService;
import de.vvwt.slotopt.worker.types.JobDef;
import de.vvwt.slotopt.worker.types.StructuralFingerprint;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default implementation of {@link JobFinalizationService}.
 *
 * <p>Per DEC-35: this implementation lives in {@code job.internal}. All consumers reference {@link
 * JobFinalizationService} (the public interface) exclusively (DEC-36).
 *
 * <p>Finalization is triggered from {@link
 * de.vvwt.slotopt.dispatcher.result.internal.DefaultSubmitResultService#submit} after a packet is
 * accepted (status → {@code RESULT_RECEIVED}). This method checks if all packets of the job are now
 * in terminal state and, if so, computes the global optimum and completes the job.
 *
 * <p>Aggregation comparator: {@code (bestScore ASC, bestRank ASC)} — total order, deterministic for
 * any set of packet results (AC-TEST-AGGREGATION-DETERMINISTIC, AC-GOV-FINALIZATION-CONFORMS-SPEC).
 *
 * <p>DEC-9: the result-cache key remains the structural fingerprint (derived from {@link
 * JobRecord#getJobDefJson()} via {@link StructuralFingerprint}) plus the V1 game-mode discriminator
 * — no UUID enters the key (AC-GOV-FINGERPRINT-STRUCTURAL).
 *
 * <p>Story: E60S03; AC-TEST-LAST-PACKET-FINALIZES, AC-TEST-NOT-FINALIZED-BEFORE-LAST,
 * AC-TEST-AGGREGATION-GLOBAL-OPTIMUM, AC-TEST-AGGREGATION-DETERMINISTIC,
 * AC-TEST-CACHE-HOLDS-AGGREGATED-OPTIMUM, AC-GOV-FINALIZATION-CONFORMS-SPEC,
 * AC-GOV-FINGERPRINT-STRUCTURAL, AC-ERR-FINALIZATION-DOES-NOT-BLOCK-RESULT-ACCEPT,
 * AC-ERR-FINALIZATION-IDEMPOTENT, AC-ERR-CACHE-WRITE-FAILURE-ABSORBED,
 * AC-ERR-LAST-PACKET-DEFINITION; DEC-9, DEC-35, DEC-36, DEC-58, DEC-72
 */
@Service
class DefaultJobFinalizationService implements JobFinalizationService {

    private static final Logger LOG =
            Logger.getLogger(DefaultJobFinalizationService.class.getName());

    /**
     * V1 game-mode discriminator — matches the value used by {@link
     * de.vvwt.slotopt.dispatcher.result.internal.DefaultSubmitResultService} for cache-key
     * consistency (DEC-9 / AC-GOV-FINGERPRINT-STRUCTURAL).
     */
    static final String V1_GAME_MODE = "default";

    /**
     * Terminal packet status. A packet is final if and only if its status equals this constant.
     * {@code UNCLAIMED} and {@code CLAIMED} are non-terminal per AC-ERR-LAST-PACKET-DEFINITION.
     * {@code TIMEDOUT} is never written by production code (the sweeper resets to {@code
     * UNCLAIMED}), but is also non-terminal by definition.
     */
    private static final String TERMINAL_STATUS = "RESULT_RECEIVED";

    private final PacketRepository packetRepository;
    private final PacketResultService packetResultService;
    private final JobRepository jobRepository;
    private final ResultsCacheService resultsCacheService;
    private final ObjectMapper objectMapper;

    DefaultJobFinalizationService(
            PacketRepository packetRepository,
            PacketResultService packetResultService,
            JobRepository jobRepository,
            ResultsCacheService resultsCacheService) {
        this.packetRepository = packetRepository;
        this.packetResultService = packetResultService;
        this.jobRepository = jobRepository;
        this.resultsCacheService = resultsCacheService;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Runs within the caller's existing transaction (joining via {@code REQUIRED} propagation).
     * Within the same H2 connection / transaction, the just-committed-in-memory packet status
     * update is visible to {@link PacketRepository#findByJobId(UUID)} — this is the correct
     * last-packet detection mechanism (no phantom-read issue because H2 reads own dirty writes
     * within the same connection).
     *
     * <p>All finalization exceptions (aggregate failure, DB failure, cache write failure) MUST be
     * caught and absorbed by the CALLER ({@link
     * de.vvwt.slotopt.dispatcher.result.internal.DefaultSubmitResultService#submit}) so that the
     * triggering packet's accept transaction is not rolled back. This method propagates any
     * exception from DB operations other than the cache write; the caller's try/catch absorbs them.
     */
    @Override
    @Transactional
    public void tryFinalizeJob(UUID jobId) {
        // Step 1: Check if all packets are terminal
        List<PacketRecord> packets = packetRepository.findByJobId(jobId);
        boolean allTerminal =
                !packets.isEmpty()
                        && packets.stream().allMatch(p -> TERMINAL_STATUS.equals(p.getStatus()));

        if (!allTerminal) {
            // Job still has non-terminal packets — not ready for finalization
            return;
        }

        // Step 2: Load the job record; guard against already-COMPLETED (idempotent)
        JobRecord jobRecord =
                jobRepository
                        .findByJobId(jobId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Job not found during finalization: " + jobId));

        if ("COMPLETED".equals(jobRecord.getStatus())) {
            // Already finalized — idempotent no-op (AC-ERR-FINALIZATION-IDEMPOTENT)
            LOG.log(
                    Level.FINE,
                    "Job {0} already COMPLETED — finalization skipped (idempotent)",
                    jobId);
            return;
        }

        // Step 3: Aggregate global optimum from per-packet results
        List<PacketResult> results = packetResultService.findResultsByJobId(jobId);
        PacketResult globalOptimum = aggregateGlobalOptimum(results, jobId);

        // Step 4: Transition job DECOMPOSED → COMPLETED
        jobRecord.setStatus("COMPLETED");
        jobRepository.save(jobRecord);
        LOG.log(
                Level.INFO,
                "Job {0} finalized: DECOMPOSED → COMPLETED (bestScore={1}, bestRank={2})",
                new Object[] {jobId, globalOptimum.getBestScore(), globalOptimum.getBestRank()});

        // Step 5: Write aggregated optimum to result cache (absorbed on failure)
        // AC-ERR-CACHE-WRITE-FAILURE-ABSORBED: cache failure does not prevent COMPLETED status.
        try {
            byte[] fingerprint = computeFingerprint(jobRecord);
            String aggregatedPayload = buildAggregatedPayload(globalOptimum);
            resultsCacheService.recordAcceptedResult(
                    fingerprint, V1_GAME_MODE, aggregatedPayload, jobId);
        } catch (Exception e) {
            LOG.log(
                    Level.WARNING,
                    "Cache write failed at finalization for job {0} — absorbed: {1}",
                    new Object[] {jobId, e.getMessage()});
        }
    }

    /**
     * Aggregates the global optimum from packet results.
     *
     * <p>Total order: {@code (bestScore ASC, bestRank ASC)}. Deterministic: for any input ordering,
     * the same winner is produced.
     */
    private PacketResult aggregateGlobalOptimum(List<PacketResult> results, UUID jobId) {
        if (results.isEmpty()) {
            throw new IllegalStateException(
                    "No packet results found for finalization of job "
                            + jobId
                            + " — all packets are RESULT_RECEIVED but no retained results exist");
        }
        Comparator<PacketResult> comparator =
                Comparator.comparingDouble(PacketResult::getBestScore)
                        .thenComparingInt(PacketResult::getBestRank);
        return results.stream()
                .min(comparator)
                .orElseThrow(
                        () -> new IllegalStateException("Empty result stream for job " + jobId));
    }

    /**
     * Constructs the aggregated result payload JSON from the winning {@link PacketResult}.
     *
     * <p>Format: {@code {"bestRank": <int>, "bestScore": <double>}} Matches the format stored by
     * per-packet results and read by the cache lookup path.
     */
    private String buildAggregatedPayload(PacketResult winner) {
        try {
            return objectMapper.writeValueAsString(
                    Map.of("bestRank", winner.getBestRank(), "bestScore", winner.getBestScore()));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to serialize aggregated payload for finalization", e);
        }
    }

    /**
     * Computes the structural fingerprint from a job's {@code jobDefJson}.
     *
     * <p>DEC-9: key is purely structural — no UUIDs enter the fingerprint
     * (AC-GOV-FINGERPRINT-STRUCTURAL).
     */
    private byte[] computeFingerprint(JobRecord jobRecord) {
        try {
            JobDef jobDef = objectMapper.readValue(jobRecord.getJobDefJson(), JobDef.class);
            return StructuralFingerprint.fingerprint(jobDef.canonicalPhaseDef());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to compute fingerprint from jobDefJson for job " + jobRecord.getJobId(),
                    e);
        }
    }
}
