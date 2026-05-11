package de.vvwt.tm.phaselifecycle;

import java.util.Optional;
import java.util.UUID;

/**
 * Public port for the DB-durable {@code phase_lifecycle_job} queue DAO (DEC-64 D-4, DEC-35,
 * DEC-58).
 *
 * <p>Provides the portable atomic Compare-and-Swap claim mechanism and basic lifecycle operations
 * on {@code phase_lifecycle_job} rows. The implementation (E55S02) uses a two-statement CAS
 * protocol: {@code SELECT} next-pending → {@code UPDATE WHERE status='PENDING'} (affected-rows
 * count proves the claim).
 *
 * <p>The sole implementation is {@link
 * de.vvwt.tm.phaselifecycle.internal.DefaultPhaseLifecycleJobRepository}, which lands in E55S02.
 *
 * <p>Authorizing decisions: DEC-64 D-4 (DB-durable queue + CAS claim), DEC-35 (interface in
 * module-root), DEC-58 (universal interface mandate — hand-authored {@code @Repository}).
 *
 * @since E55S01
 */
public interface PhaseLifecycleJobRepository {

    /**
     * Finds the next pending job for the given tournament in FIFO order (by sequence, then
     * enqueued_at).
     *
     * <p>This is step 1 of the two-statement CAS protocol. Returns an empty {@code Optional} when
     * no PENDING rows exist for the tournament.
     *
     * @param tournamentId the tournament to query
     * @return the {@code id} of the next candidate row, or empty
     */
    Optional<UUID> findNextPendingJobIdForTournament(UUID tournamentId);

    /**
     * Atomically claims the job by transitioning {@code status='PENDING' → 'RUNNING'} iff the row
     * is still PENDING (portable CAS via single-statement {@code UPDATE WHERE id=? AND
     * status='PENDING'}).
     *
     * <p>This is step 2 of the two-statement CAS protocol. Returns {@code true} iff {@code
     * affectedRows == 1} (claim succeeded); {@code false} if another worker already claimed it.
     *
     * @param jobId the candidate row id returned by {@link
     *     #findNextPendingJobIdForTournament(UUID)}
     * @param claimedBy JVM-instance identifier written to {@code claimed_by}
     * @return true if the claim was successful
     */
    boolean tryClaim(UUID jobId, String claimedBy);

    /**
     * Marks the job as successfully completed ({@code status='COMPLETED'}, sets {@code
     * completed_at}).
     *
     * <p><b>Contract when job is not {@code RUNNING} (chosen contract per E55S02
     * AC-ERROR-HANDLING-MARK-COMPLETED-NOT-RUNNING, option b):</b> if the row's status is not
     * {@code 'RUNNING'}, this method is a no-op and logs a WARN. No exception is thrown. Rationale:
     * restart-recovery scenarios may cause duplicate {@code markCompleted} calls on an already
     * {@code COMPLETED} row; a no-op is safer than an exception that would interrupt recovery.
     *
     * @param jobId the claimed row id
     */
    void markCompleted(UUID jobId);

    /**
     * Marks the job's {@code cancelled} flag as {@code TRUE} (cooperative cancel signal per DEC-64
     * D-10). Does not change {@code status} — the running orchestrator observes the flag at its
     * next poll point and applies Best-So-Far semantics.
     *
     * @param jobId the running row id for the cancelled tournament
     */
    void markCancelled(UUID jobId);

    /**
     * Inserts a new job row with {@code status='PENDING'} into the queue. Called by {@link
     * de.vvwt.tm.tournament.internal.DefaultDraftService#apply} (E55S06) in the same TX as phase +
     * avatar creation.
     *
     * @param job the job to enqueue
     */
    void enqueueJob(PhaseLifecycleJob job);

    /**
     * Loads the execution-time projection of a job row (jobId + phaseId + gameMode +
     * tournamentId). Called by the orchestrator immediately after a successful {@link
     * #tryClaim(UUID, String)} to retrieve the fields needed to drive the pipeline.
     *
     * <p>Returns an empty {@code Optional} if the row does not exist (defensive path; in normal
     * operation the row was just claimed and will always exist).
     *
     * @param jobId the primary key of the row to load
     * @return the execution-time projection, or empty if not found
     * @since E55S04
     */
    Optional<PhaseLifecycleJobDetails> findJobDetailsById(UUID jobId);
}
