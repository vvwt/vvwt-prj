package de.vvwt.tm.phaselifecycle;

import java.util.UUID;

/**
 * Public port for the REQUIRES_NEW failure-state writer that persists {@code
 * phase.last_job_state='failed'} independently of the rolled-back step-A or step-B transaction
 * (DEC-58 universal-interface-mandate, DEC-64 D-12, DEC-66 D-2).
 *
 * <p>The {@code 'failed'} write MUST commit even when the orchestrator step's enclosing TX is
 * rolling back. This is achieved by wrapping the write in a
 * {@code @Transactional(propagation=REQUIRES_NEW)} boundary in the implementation — analogous to
 * the pre-E55 {@code DefaultMatchGenFailureWriter} pattern (cited in DEC-66 D-2).
 *
 * <p>DEC-58 mandate: public interface in module-root ({@code de.vvwt.tm.phaselifecycle}), {@code
 * Default*} implementation in {@code .internal} ({@link
 * de.vvwt.tm.phaselifecycle.internal.DefaultPhaseLastJobStateWriter}).
 *
 * <p>Authorizing decisions: DEC-58 (universal interface mandate), DEC-64 D-12 (TX granularity),
 * DEC-66 D-2 (failure writer via REQUIRES_NEW), AC-IMPL-LAST-JOB-STATE-STEP-FAILURE-FAILED.
 *
 * @since E55S08
 */
public interface PhaseLastJobStateWriter {

    /**
     * Writes {@code phase.last_job_state='failed'} for the given phase in a fresh {@code
     * REQUIRES_NEW} transaction, ensuring the write commits independently of any surrounding
     * transaction rollback.
     *
     * <p>Called by the orchestrator's exception-handling path (step-A failure or step-B failure).
     * The surrounding TX of the failed step will roll back; this method's REQUIRES_NEW TX commits
     * the failure record independently.
     *
     * <p>If {@code phaseId} does not correspond to an existing phase row, this method is a no-op
     * (defensive — the phase may have been deleted by a concurrent operation; the failure record
     * becomes irrelevant in that case).
     *
     * @param phaseId the phase whose {@code last_job_state} should be set to {@code 'failed'}
     */
    void writeFailedState(UUID phaseId);
}
