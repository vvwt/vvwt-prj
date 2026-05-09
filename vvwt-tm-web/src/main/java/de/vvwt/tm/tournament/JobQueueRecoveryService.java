package de.vvwt.tm.tournament;

/**
 * Public interface for the restart-recovery service component (DEC-58 Clause A operationalization).
 *
 * <p>The sole implementation is {@link
 * de.vvwt.tm.tournament.internal.DefaultJobQueueRecoveryService} in {@code tournament.internal} per
 * DEC-35 §1 (as amended by DEC-58).
 *
 * <p>Reconciles in-flight background jobs after a JVM restart (DEC-55 D-8, E51S07). On {@code
 * ApplicationReadyEvent}, scans {@code phase.last_job_state} and re-publishes events to restart the
 * pipeline.
 *
 * @see de.vvwt.tm.tournament.internal.DefaultJobQueueRecoveryService
 * @see <a href="DEC-55">DEC-55 D-8 — Restart-Recovery</a>
 * @see <a href="DEC-58">DEC-58 — Universal interface mandate for self-created Spring components</a>
 */
public interface JobQueueRecoveryService {

    /**
     * Triggered on {@code ApplicationReadyEvent} to recover in-flight background jobs after a
     * restart.
     */
    void recover();
}
