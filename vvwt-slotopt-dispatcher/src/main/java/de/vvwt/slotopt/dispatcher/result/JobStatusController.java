// SPDX-FileCopyrightText: Copyright (C) 2026 Thomas Steinke
// SPDX-License-Identifier: AGPL-3.0-or-later
package de.vvwt.slotopt.dispatcher.result;

import de.vvwt.slotopt.dispatcher.job.JobRecord;
import de.vvwt.slotopt.dispatcher.job.JobRepository;
import de.vvwt.slotopt.dispatcher.packet.PacketRecord;
import de.vvwt.slotopt.dispatcher.packet.PacketRepository;
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
 * <p>Per DEC-36: this controller injects {@code JobRepository} and {@code PacketRepository} via
 * their public Spring Data interfaces (Spring Data IS the port per DEC-35).
 *
 * <p>Story: E37S09; AC-JOB-STATUS-CONTROLLER; DEC-35, DEC-36
 */
@RestController
public class JobStatusController {

    private final JobRepository jobRepository;
    private final PacketRepository packetRepository;

    public JobStatusController(JobRepository jobRepository, PacketRepository packetRepository) {
        this.jobRepository = jobRepository;
        this.packetRepository = packetRepository;
    }

    /**
     * Returns the aggregated status for a job.
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

        JobStatusResponse response =
                new JobStatusResponse(jobId, job.getStatus(), totalPackets, (int) completedPackets);
        return ResponseEntity.ok(response);
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
