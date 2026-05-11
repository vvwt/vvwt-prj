package de.vvwt.tm.phaselifecycle.internal;

import de.vvwt.tm.phaselifecycle.JobDrainService;
import de.vvwt.tm.phaselifecycle.PhaseLifecycleJobRepository;
import java.net.InetAddress;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Default implementation of {@link JobDrainService} (DEC-64 D-3, DEC-64 D-12).
 *
 * <p>The drain service is the glue between the per-tournament worker thread (E55S03 {@link
 * de.vvwt.tm.phaselifecycle.WorkerRegistry}) and the Saga-Orchestrator ({@link
 * DefaultPhaseLifecycleOrchestrator}). One {@code drainNext()} call:
 *
 * <ol>
 *   <li>Finds the next pending job id via {@link
 *       PhaseLifecycleJobRepository#findNextPendingJobIdForTournament(UUID)}.
 *   <li>Atomically claims it via {@link PhaseLifecycleJobRepository#tryClaim(UUID, String)} (CAS:
 *       {@code UPDATE WHERE status='PENDING'} — affected-rows = 1 proves the claim). This is the
 *       T-claim transaction boundary per DEC-64 D-12 — the repository method handles its own TX.
 *   <li>On successful claim, delegates to {@link DefaultPhaseLifecycleOrchestrator#executeClaimed}
 *       for the T-job-step-A + T-job-step-B pipeline.
 *   <li>Returns silently if no pending job exists or the claim race is lost.
 * </ol>
 *
 * <h2>DEC-37 Clause B</h2>
 *
 * <p>DEC-37 Clause B's per-tournament row-lock is acquired inside T-job-step-A and T-job-step-B,
 * NOT in T-claim. T-claim is a narrow CAS-only TX (touches only {@code phase_lifecycle_job}).
 *
 * <p>Authorizing decisions: DEC-35, DEC-37 Clause B, DEC-44, DEC-58, DEC-64 D-3, DEC-64 D-12,
 * DEC-64 D-14.
 *
 * @since E55S04
 */
@Service("jobDrainService")
public class DefaultJobDrainService implements JobDrainService {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultJobDrainService.class);

    private final PhaseLifecycleJobRepository jobRepository;
    private final DefaultPhaseLifecycleOrchestrator orchestrator;
    private final String claimedBy;

    public DefaultJobDrainService(
            PhaseLifecycleJobRepository jobRepository,
            DefaultPhaseLifecycleOrchestrator orchestrator) {
        this.jobRepository = jobRepository;
        this.orchestrator = orchestrator;
        this.claimedBy = resolveClaimedBy();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Claims and executes the next pending job for the given tournament. No-op if no pending job
     * exists or the CAS claim is lost to a concurrent worker (race condition in multi-node deploy).
     */
    @Override
    public void drainNext(UUID tournamentId) {
        // Step 1: find next candidate job id (SELECT — no lock)
        Optional<UUID> jobIdOpt = jobRepository.findNextPendingJobIdForTournament(tournamentId);
        if (jobIdOpt.isEmpty()) {
            LOG.debug(
                    "DefaultJobDrainService.drainNext: no pending job for tournamentId={}",
                    tournamentId);
            return;
        }
        UUID jobId = jobIdOpt.get();

        // Step 2: T-claim — CAS UPDATE WHERE status='PENDING'; proves ownership
        boolean claimed = jobRepository.tryClaim(jobId, claimedBy);
        if (!claimed) {
            // Another worker claimed the row concurrently — no work to do
            LOG.info(
                    "DefaultJobDrainService.drainNext: CAS claim lost for jobId={}"
                            + " tournamentId={} — skipping",
                    jobId,
                    tournamentId);
            return;
        }

        LOG.info(
                "DefaultJobDrainService.drainNext: claimed jobId={} tournamentId={}",
                jobId,
                tournamentId);

        // Step 3: execute the claimed job via the Saga-Orchestrator pipeline
        // (T-job-step-A + T-job-step-B — each in its own REQUIRES_NEW TX)
        orchestrator.executeClaimed(jobId);
    }

    /**
     * Resolves a JVM-instance identifier for the {@code claimed_by} column.
     *
     * <p>Falls back to {@code "unknown-host"} if the hostname cannot be resolved.
     */
    private static String resolveClaimedBy() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-host";
        }
    }
}
