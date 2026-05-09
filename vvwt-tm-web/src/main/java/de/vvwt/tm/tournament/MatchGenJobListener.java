package de.vvwt.tm.tournament;

import de.vvwt.tm.tournament.events.MatchGenJobScheduledEvent;

/**
 * Public interface for the Match-Gen job listener component (DEC-58 Clause A operationalization).
 *
 * <p>The sole implementation is {@link de.vvwt.tm.tournament.internal.DefaultMatchGenJobListener}
 * in {@code tournament.internal} per DEC-35 §1 (as amended by DEC-58).
 *
 * <p>Async listener that consumes {@link MatchGenJobScheduledEvent} AFTER the apply-transaction
 * commits and delegates to {@link MatchGenJobExecutor} to run match-generation in its own TX
 * (DEC-55 D-3, E51S03).
 *
 * <p><strong>DEC-36 listener carve-out (DEC-58 Clause E):</strong> No first-party application code
 * injects this listener bean; the only consumer is the Spring framework itself (via
 * {@code @TransactionalEventListener} method discovery on the impl class). DEC-36's
 * cross-package-test-must-mock-interface rule does not apply to listener bean classes.
 *
 * @see de.vvwt.tm.tournament.internal.DefaultMatchGenJobListener
 * @see <a href="DEC-55">DEC-55 D-3 — Background-Job-Pipeline (events-only)</a>
 * @see <a href="DEC-58">DEC-58 — Universal interface mandate for self-created Spring components</a>
 */
public interface MatchGenJobListener {

    /**
     * Consumes {@link MatchGenJobScheduledEvent} after the publishing TX commits and delegates to
     * {@link MatchGenJobExecutor#execute(java.util.UUID, java.util.UUID)}.
     *
     * @param event the match-gen job event; carries {@code tournamentId} and {@code phaseId}
     */
    void onMatchGenJobScheduled(MatchGenJobScheduledEvent event);
}
