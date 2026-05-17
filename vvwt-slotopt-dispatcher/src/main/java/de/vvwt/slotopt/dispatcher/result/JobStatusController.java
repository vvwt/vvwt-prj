// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.result;

import de.vvwt.slotopt.dispatcher.job.JobRecord;
import de.vvwt.slotopt.dispatcher.job.JobRepository;
import de.vvwt.slotopt.dispatcher.packet.PacketRecord;
import de.vvwt.slotopt.dispatcher.packet.PacketRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for job status queries.
 *
 * <p>Handles {@code GET /api/job-status/{jobId}}. No authentication required in Phase 1 (per spec
 * section (b) Endpoint 6).
 *
 * <p>HTTP status codes:
 *
 * <ul>
 *   <li>200 OK — job found; returns {@link JobStatusResponse}
 *   <li>404 Not Found — unknown job UUID
 * </ul>
 *
 * <p>Result surfaces (E60S04):
 *
 * <ul>
 *   <li>{@code finalResult} — populated when the job is {@code COMPLETED}: the aggregated global
 *       optimum across all packets, read from the {@link PacketResultService} retention store.
 *   <li>{@code bestSoFar} — populated when the job is NOT {@code COMPLETED} and at least one packet
 *       result is retained: the best result among completed packets at query time.
 * </ul>
 *
 * <p>Aggregation comparator: {@code (bestScore ASC, bestRank ASC)} — same total-order as {@link
 * de.vvwt.slotopt.dispatcher.job.internal.DefaultJobFinalizationService}, ensuring {@code
 * bestSoFar} and {@code finalResult} use the same winner-selection semantics.
 *
 * <p>Per DEC-36: this controller injects {@link JobRepository} and {@link PacketRepository} via
 * their public Spring Data interfaces (Spring Data IS the port per DEC-35), and injects {@link
 * PacketResultService} via its public service interface (DEC-35/DEC-58/DEC-72).
 *
 * <p>Per DEC-9: {@code finalResult} and {@code bestSoFar} carry only structural optimization data
 * ({@code bestRank} / {@code bestScore}) — no team UUIDs, names, or identity-bearing attributes
 * cross the optimizer service boundary (AC-GOV-DEC9-STRUCTURAL-ONLY).
 *
 * <p>Per AC-GOV-DELIVERED-ENDPOINT-AND-ENUM: this controller serves the delivered path {@code GET
 * /api/job-status/{jobId}} and returns the delivered status values {@code RECEIVED} / {@code
 * DECOMPOSED} / {@code COMPLETED}, NOT the stale E37-spec paths.
 *
 * <p>Per AC-GOV-RESPONSE-EXTENSION-BOUNDED: the granular packet-count breakdown ({@code
 * packetsAssigned} / {@code packetsPending} / {@code packetsFailed}) is NOT added.
 *
 * <p>Story: E37S09; E60S04; AC-JOB-STATUS-CONTROLLER; AC-TEST-JOB-STATUS-REPORTS-LIFECYCLE;
 * AC-TEST-JOB-STATUS-FINAL-RESULT; AC-TEST-JOB-STATUS-BEST-SO-FAR; AC-ERR-UNKNOWN-JOB-404;
 * AC-ERR-BEST-SO-FAR-NO-COMPLETED-PACKETS; DEC-9, DEC-35, DEC-36, DEC-58, DEC-72
 */
@RestController
public class JobStatusController {

    private static final Comparator<PacketResult> OPTIMUM_COMPARATOR =
            Comparator.comparingDouble(PacketResult::getBestScore)
                    .thenComparingInt(PacketResult::getBestRank);

    private final JobRepository jobRepository;
    private final PacketRepository packetRepository;
    private final PacketResultService packetResultService;

    public JobStatusController(
            JobRepository jobRepository,
            PacketRepository packetRepository,
            PacketResultService packetResultService) {
        this.jobRepository = jobRepository;
        this.packetRepository = packetRepository;
        this.packetResultService = packetResultService;
    }

    /**
     * Returns the aggregated status for a job, including result surfaces when available.
     *
     * @param jobId the job UUID
     * @return 200 with {@link JobStatusResponse}, or 404 if unknown
     */
    @GetMapping("/api/job-status/{jobId}")
    public ResponseEntity<JobStatusResponse> getJobStatus(@PathVariable("jobId") UUID jobId) {
        JobRecord job =
                jobRepository
                        .findByJobId(jobId)
                        .orElseThrow(() -> new JobNotFoundException("Unknown job: " + jobId));

        List<PacketRecord> packets = packetRepository.findByJobId(jobId);
        int totalPackets = packets.size();
        long completedPackets =
                packets.stream().filter(p -> "RESULT_RECEIVED".equals(p.getStatus())).count();

        // Load retained packet results for bestSoFar / finalResult computation (E60S04)
        List<PacketResult> retainedResults = packetResultService.findResultsByJobId(jobId);

        OptimumResult finalResult = null;
        OptimumResult bestSoFar = null;

        if ("COMPLETED".equals(job.getStatus())) {
            // AC-TEST-JOB-STATUS-FINAL-RESULT: when COMPLETED, expose finalResult only.
            // bestSoFar is null for completed jobs (use finalResult).
            finalResult = computeOptimum(retainedResults);
        } else {
            // AC-TEST-JOB-STATUS-BEST-SO-FAR: in-progress → expose bestSoFar.
            // AC-ERR-BEST-SO-FAR-NO-COMPLETED-PACKETS: null if no retained results yet.
            bestSoFar = computeOptimum(retainedResults);
        }

        JobStatusResponse response =
                new JobStatusResponse(
                        jobId,
                        job.getStatus(),
                        totalPackets,
                        (int) completedPackets,
                        finalResult,
                        bestSoFar);
        return ResponseEntity.ok(response);
    }

    /**
     * Computes the best result across the provided retained packet results.
     *
     * <p>Returns null if {@code results} is empty (AC-ERR-BEST-SO-FAR-NO-COMPLETED-PACKETS). Uses
     * the same {@code (bestScore ASC, bestRank ASC)} total-order as {@link
     * de.vvwt.slotopt.dispatcher.job.internal.DefaultJobFinalizationService}.
     */
    private OptimumResult computeOptimum(List<PacketResult> results) {
        if (results.isEmpty()) {
            return null;
        }
        PacketResult winner =
                results.stream()
                        .min(OPTIMUM_COMPARATOR)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "Empty result stream (impossible)"));
        return new OptimumResult(winner.getBestRank(), winner.getBestScore());
    }

    /** HTTP 404 — unknown job. */
    @ExceptionHandler(JobNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleJobNotFound(JobNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    /** Internal exception for unknown job UUIDs. */
    static class JobNotFoundException extends RuntimeException {
        JobNotFoundException(String message) {
            super(message);
        }
    }
}
