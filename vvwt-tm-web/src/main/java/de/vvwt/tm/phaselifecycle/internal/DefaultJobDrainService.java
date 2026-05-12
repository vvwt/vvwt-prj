package de.vvwt.tm.phaselifecycle.internal;

import de.vvwt.tm.phaselifecycle.JobDrainService;
import de.vvwt.tm.phaselifecycle.PhaseLifecycleJobDetails;
import de.vvwt.tm.phaselifecycle.PhaseLifecycleJobRepository;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseRepository;
import java.net.InetAddress;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
 *   <li>On successful claim, writes {@code phase.last_job_state='match_gen_running'} in a tight
 *       {@code REQUIRES_NEW} TX immediately after T-claim (DEC-66 D-2,
 *       AC-IMPL-LAST-JOB-STATE-AT-CLAIM). Only reached when claim CAS succeeded (no write on
 *       race-loss per AC-ERROR-HANDLING-CLAIM-WRITE-ATOMIC).
 *   <li>Delegates to {@link DefaultPhaseLifecycleOrchestrator#executeClaimed} for the T-job-step-A
 *       + T-job-step-B pipeline.
 *   <li>Returns silently if no pending job exists or the claim race is lost.
 * </ol>
 *
 * <h2>DEC-37 Clause B</h2>
 *
 * <p>DEC-37 Clause B's per-tournament row-lock is acquired inside T-job-step-A and T-job-step-B,
 * NOT in T-claim. T-claim is a narrow CAS-only TX (touches only {@code phase_lifecycle_job}).
 *
 * <p>Authorizing decisions: DEC-35, DEC-37 Clause B, DEC-44, DEC-58, DEC-64 D-3, DEC-64 D-12,
 * DEC-64 D-14, DEC-66 D-2, AC-IMPL-LAST-JOB-STATE-AT-CLAIM, AC-ERROR-HANDLING-CLAIM-WRITE-ATOMIC.
 *
 * @since E55S04
 * @updated E55S08 (inject {@link PhaseRepository}; write {@code match_gen_running} at T-claim per
 *     DEC-66 D-2 and AC-IMPL-LAST-JOB-STATE-AT-CLAIM)
 */
@Service("jobDrainService")
public class DefaultJobDrainService implements JobDrainService {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultJobDrainService.class);

    /** Enum value written at T-claim time per DEC-66 D-2. */
    static final String MATCH_GEN_RUNNING = "match_gen_running";

    private final PhaseLifecycleJobRepository jobRepository;
    private final PhaseRepository phaseRepository;
    private final DefaultPhaseLifecycleOrchestrator orchestrator;
    private final String claimedBy;

    public DefaultJobDrainService(
            PhaseLifecycleJobRepository jobRepository,
            @Qualifier("tmPhaseRepository") PhaseRepository phaseRepository,
            DefaultPhaseLifecycleOrchestrator orchestrator) {
        this.jobRepository = jobRepository;
        this.phaseRepository = phaseRepository;
        this.orchestrator = orchestrator;
        this.claimedBy = resolveClaimedBy();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Claims and executes all pending jobs for the given tournament in FIFO order (DEC-64 D-4).
     * After each successful CAS claim, writes {@code phase.last_job_state='match_gen_running'}
     * immediately at T-claim time per DEC-66 D-2 and AC-IMPL-LAST-JOB-STATE-AT-CLAIM. Loops until
     * the queue is empty or a CAS claim is lost to a concurrent worker. No-op if no pending job
     * exists.
     */
    @Override
    public void drainNext(UUID tournamentId) {
        // Loop: process all pending jobs for this tournament in FIFO sequence (DEC-64 D-4).
        // Terminates when queue is empty or CAS claim is lost to a concurrent worker.
        while (true) {
            // Step 1: find next candidate job id (SELECT — no lock)
            Optional<UUID> jobIdOpt = jobRepository.findNextPendingJobIdForTournament(tournamentId);
            if (jobIdOpt.isEmpty()) {
                LOG.debug(
                        "DefaultJobDrainService.drainNext: no pending job for tournamentId={}"
                                + " — queue exhausted",
                        tournamentId);
                return;
            }
            UUID jobId = jobIdOpt.get();

            // Step 2: T-claim — CAS UPDATE WHERE status='PENDING'; proves ownership
            boolean claimed = jobRepository.tryClaim(jobId, claimedBy);
            if (!claimed) {
                // Another worker claimed the row concurrently — that worker will continue draining
                LOG.info(
                        "DefaultJobDrainService.drainNext: CAS claim lost for jobId={}"
                                + " tournamentId={} — concurrent worker is draining",
                        jobId,
                        tournamentId);
                return;
            }

            LOG.info(
                    "DefaultJobDrainService.drainNext: claimed jobId={} tournamentId={}",
                    jobId,
                    tournamentId);

            // Step 2b: write phase.last_job_state='match_gen_running' at T-claim time.
            // Only reached when CAS claim succeeded (affectedRows=1) per
            // AC-ERROR-HANDLING-CLAIM-WRITE-ATOMIC.
            writeMatchGenRunning(jobId);

            // Step 3: execute the claimed job via the Saga-Orchestrator pipeline
            // (T-job-step-A + T-job-step-B — each in its own REQUIRES_NEW TX)
            orchestrator.executeClaimed(jobId);
            // Loop continues: pick up the next PENDING job for this tournament (FIFO order)
        }
    }

    /**
     * Writes {@code phase.last_job_state='match_gen_running'} for the phase associated with the
     * given job in a fresh {@code REQUIRES_NEW} transaction (DEC-66 D-2,
     * AC-IMPL-LAST-JOB-STATE-AT-CLAIM).
     *
     * <p>Called immediately after a successful CAS claim. Only reached when the CAS claim succeeded
     * — no write on race-loss per AC-ERROR-HANDLING-CLAIM-WRITE-ATOMIC.
     *
     * <p>If the job details or phase cannot be found (defensive path), logs a WARN and returns
     * without error — the job execution will also fail for the same reason.
     *
     * @param jobId the already-claimed job row id
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void writeMatchGenRunning(UUID jobId) {
        PhaseLifecycleJobDetails details = jobRepository.findJobDetailsById(jobId).orElse(null);
        if (details == null) {
            LOG.warn(
                    "DefaultJobDrainService.writeMatchGenRunning: jobId={} not found — no-op",
                    jobId);
            return;
        }
        UUID phaseId = details.phaseId();
        Phase phase = phaseRepository.findById(phaseId).orElse(null);
        if (phase == null) {
            LOG.warn(
                    "DefaultJobDrainService.writeMatchGenRunning: phaseId={} not found — no-op",
                    phaseId);
            return;
        }
        phase.setLastJobState(MATCH_GEN_RUNNING);
        phaseRepository.save(phase);
        LOG.info(
                "DefaultJobDrainService.writeMatchGenRunning: last_job_state='match_gen_running'"
                        + " phaseId={} jobId={}",
                phaseId,
                jobId);
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
