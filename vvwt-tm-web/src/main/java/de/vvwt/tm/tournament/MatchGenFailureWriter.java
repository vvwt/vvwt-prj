package de.vvwt.tm.tournament;

import java.util.UUID;

/**
 * Public interface for the Match-Gen failure writer component (DEC-58 Clause A operationalization).
 *
 * <p>The sole implementation is {@link de.vvwt.tm.tournament.internal.DefaultMatchGenFailureWriter}
 * in {@code tournament.internal} per DEC-35 §1 (as amended by DEC-58).
 *
 * <p>Writes {@code phase.last_job_state = 'failed'} in a fresh {@code REQUIRES_NEW} transaction.
 * Extracted from {@code MatchGenJobListener} to avoid Spring AOP self-invocation bypass (calling a
 * {@code @Transactional(REQUIRES_NEW)} method on {@code this} from within the same bean skips the
 * proxy and inherits the caller's rolling-back transaction).
 *
 * <p><strong>DEC-36 listener carve-out (DEC-58 Clause E):</strong> This component is called
 * exclusively from {@link de.vvwt.tm.tournament.internal.DefaultMatchGenJobListener}'s catch block
 * (intra-{@code tournament.internal} call, not a cross-package consumer). DEC-36's
 * cross-package-test-must-mock-interface rule does not restrict intra-{@code internal} usage.
 *
 * @see de.vvwt.tm.tournament.internal.DefaultMatchGenFailureWriter
 * @see <a href="DEC-55">DEC-55 D-3 — Background-Job-Pipeline (events-only)</a>
 * @see <a href="DEC-58">DEC-58 — Universal interface mandate for self-created Spring components</a>
 */
public interface MatchGenFailureWriter {

    /**
     * Writes {@code phase.last_job_state = 'failed'} in a new independent transaction.
     *
     * @param phaseId the phase UUID to mark as failed
     */
    void writeFailedState(UUID phaseId);
}
