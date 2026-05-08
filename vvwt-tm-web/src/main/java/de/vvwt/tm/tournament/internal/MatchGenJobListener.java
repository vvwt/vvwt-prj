package de.vvwt.tm.tournament.internal;

import de.vvwt.tm.tournament.events.MatchGenJobScheduledEvent;
import de.vvwt.tm.tournament.events.SlotOptJobScheduledEvent;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Async listener that consumes {@link MatchGenJobScheduledEvent} AFTER the apply-transaction
 * commits and delegates to {@link MatchGenJobExecutor} to run match-generation in its own TX
 * (DEC-55 D-3, E51S03).
 *
 * <h2>Transaction model (DEC-37 Clause B)</h2>
 *
 * <ol>
 *   <li>{@code @TransactionalEventListener(phase = AFTER_COMMIT)} — listener fires only after the
 *       publishing transaction commits. This ensures structural avatars (E51S02) are fully visible
 *       to the executor's new transaction.
 *   <li>{@code @Async} — listener runs in a separate thread (the {@code taskExecutor} bean
 *       configured in {@link MatchGenAsyncConfig}). The publishing thread (apply) is not blocked.
 *   <li>This listener is NOT {@code @Transactional} — it is a thin dispatcher that catches
 *       exceptions and calls failure-handling. The actual match-generation runs inside {@link
 *       MatchGenJobExecutor#execute(UUID, UUID)}'s own {@code @Transactional(REQUIRES_NEW)}.
 *   <li>Separating the transactional work into {@link MatchGenJobExecutor} avoids the Spring AOP
 *       pitfall: a {@code @Transactional(REQUIRES_NEW)} method that catches its own exception and
 *       returns normally still has a rollback-only TX, causing {@code UnexpectedRollbackException}
 *       at commit time — which would escape the method and appear as an uncaught async exception.
 * </ol>
 *
 * <h2>Idempotency (AC-ERROR-HANDLING-IDEMPOTENT-MATCH-GEN)</h2>
 *
 * <p>Idempotency is enforced inside {@link MatchGenJobExecutor#execute}: if {@code
 * phase.last_job_state} is already {@code "idle"}, the executor skips re-invocation.
 *
 * <h2>Error handling (AC-ERROR-HANDLING-LISTENER-EXCEPTION-NO-SILENT-LOSS)</h2>
 *
 * <p>If {@link MatchGenJobExecutor#execute} throws, this listener:
 *
 * <ol>
 *   <li>Logs at ERROR level with {@code tournamentId + phaseId + stackTrace}.
 *   <li>Sets {@code phase.last_job_state = 'failed'} via {@link
 *       MatchGenFailureWriter#writeFailedState} (separate REQUIRES_NEW TX).
 *   <li>Does NOT re-throw (prevents crashing the executor thread).
 * </ol>
 *
 * <h2>Error-handling transaction pattern (AC-ERROR-HANDLING-NO-PARTIAL-PERSISTENCE)</h2>
 *
 * <p>When {@link MatchGenJobExecutor#execute} throws, its TX rolls back — no partial match rows
 * persist. {@link MatchGenFailureWriter#writeFailedState} opens an independent REQUIRES_NEW TX to
 * commit {@code last_job_state='failed'}.
 *
 * @see MatchGenJobScheduledEvent
 * @see SlotOptJobScheduledEvent
 * @see MatchGenJobExecutor
 * @see MatchGenFailureWriter
 * @see <a href="DEC-55">DEC-55 D-3 — Background-Job-Pipeline (events-only)</a>
 * @see <a href="DEC-37">DEC-37 Clause B — pessimistic row-lock as first read</a>
 * @see <a href="E51S03">E51S03 — Background-Job-Pipeline foundation</a>
 */
@Component
class MatchGenJobListener {

    private static final Logger LOG = LoggerFactory.getLogger(MatchGenJobListener.class);

    private final MatchGenJobExecutor executor;
    private final MatchGenFailureWriter failureWriter;

    /**
     * Constructs the listener.
     *
     * @param executor executes match-generation in a REQUIRES_NEW TX (DEC-37 Clause B)
     * @param failureWriter writes {@code last_job_state='failed'} in a REQUIRES_NEW TX on error
     */
    MatchGenJobListener(MatchGenJobExecutor executor, MatchGenFailureWriter failureWriter) {
        this.executor = executor;
        this.failureWriter = failureWriter;
    }

    /**
     * Consumes {@link MatchGenJobScheduledEvent} after the publishing TX commits and delegates to
     * {@link MatchGenJobExecutor#execute(UUID, UUID)}.
     *
     * @param event the match-gen job event; carries {@code tournamentId} and {@code phaseId}
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onMatchGenJobScheduled(MatchGenJobScheduledEvent event) {
        UUID tournamentId = event.tournamentId();
        UUID phaseId = event.phaseId();

        LOG.info(
                "MatchGenJobListener: received event tournamentId={}, phaseId={}",
                tournamentId,
                phaseId);

        try {
            executor.execute(tournamentId, phaseId);
        } catch (Exception ex) {
            // AC-ERROR-HANDLING-LISTENER-EXCEPTION-NO-SILENT-LOSS: log at ERROR with full context
            LOG.error(
                    "MatchGenJobListener: FAILED tournamentId={}, phaseId={} — setting"
                            + " last_job_state='failed'",
                    tournamentId,
                    phaseId,
                    ex);
            // AC-ERROR-HANDLING-NO-PARTIAL-PERSISTENCE: write 'failed' in a REQUIRES_NEW TX
            failureWriter.writeFailedState(phaseId);
        }
    }
}
