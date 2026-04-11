package de.vvwt.dispatcher.result;

import de.vvwt.dispatcher.cache.ResultsCacheService;
import de.vvwt.dispatcher.job.JobRecord;
import de.vvwt.dispatcher.job.JobRepository;
import de.vvwt.dispatcher.packet.PacketRecord;
import de.vvwt.dispatcher.packet.PacketRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * REST controller for {@code GET /jobs/{jobId}} (E01S08 AC12).
 *
 * <p>Returns the current status and packet counts for a job, along with the
 * best-so-far result and the final result (if finalized).
 *
 * <p>Read-only, no authentication required (Phase 1 — public read per AC12).
 *
 * <p>See Story E01S08 AC12.
 */
@RestController
public class JobStatusController {

    private final JobRepository jobRepository;
    private final PacketRepository packetRepository;
    private final ResultsCacheService cacheService;

    public JobStatusController(JobRepository jobRepository,
                               PacketRepository packetRepository,
                               ResultsCacheService cacheService) {
        this.jobRepository  = jobRepository;
        this.packetRepository = packetRepository;
        this.cacheService   = cacheService;
    }

    /**
     * Returns the status, packet counts, and best result for a job.
     *
     * @param jobId the job UUID
     * @return 200 with {@link JobStatusResponse}, or 404 if unknown
     */
    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<?> getJobStatus(@PathVariable("jobId") UUID jobId) {
        Optional<JobRecord> jobOpt = jobRepository.findById(jobId);
        if (jobOpt.isEmpty()) {
            return ResponseEntity.status(404)
                    .body(Map.of("error", "Job not found: " + jobId));
        }

        JobRecord job = jobOpt.get();

        long completed = packetRepository.countByJobIdAndStatus(jobId, "done");
        long assigned  = packetRepository.countByJobIdAndStatus(jobId, "assigned");
        long pending   = packetRepository.countByJobIdAndStatus(jobId, "pending");
        long failed    = packetRepository.countByJobIdAndStatus(jobId, "failed");

        // bestSoFar: minimum score (then rank) across all completed packets' first results
        List<PacketRecord> donePackets = packetRepository.findDonePacketsByJobId(jobId);
        JobStatusResponse.ResultSummary bestSoFar = donePackets.stream()
                .filter(p -> p.getFirstBestRank() != null && p.getFirstBestScore() != null)
                .min(Comparator
                        .comparingDouble(PacketRecord::getFirstBestScore)
                        .thenComparingLong(PacketRecord::getFirstBestRank))
                .map(p -> new JobStatusResponse.ResultSummary(
                        p.getFirstBestRank(), p.getFirstBestScore()))
                .orElse(null);

        // finalResult: non-null only when job is done (read from cache)
        JobStatusResponse.ResultSummary finalResult = null;
        if ("done".equals(job.getStatus())) {
            int scoreFnVersion = de.vvwt.worker.score.VarietyScorer.SCORE_FN_VERSION;
            int canonicalizationVersion = de.vvwt.worker.types.StructuralFingerprint.CANONICALIZATION_VERSION;
            cacheService.lookup(job.getFingerprint(), scoreFnVersion, canonicalizationVersion)
                    .ifPresent(cached -> {
                        // captured in a local — finalResult reassignment not possible in lambda
                    });
            // Re-fetch to allow assignment
            var cachedOpt = cacheService.lookup(
                    job.getFingerprint(), scoreFnVersion, canonicalizationVersion);
            if (cachedOpt.isPresent()) {
                finalResult = new JobStatusResponse.ResultSummary(
                        cachedOpt.get().bestRank(), cachedOpt.get().bestScore());
            }
        }

        JobStatusResponse response = new JobStatusResponse(
                jobId,
                job.getStatus(),
                job.getPacketCount(),
                completed,
                assigned,
                pending,
                failed,
                bestSoFar,
                finalResult);

        return ResponseEntity.ok(response);
    }
}
