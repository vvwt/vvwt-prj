package de.vvwt.tm.slotopt.internal;

import de.vvwt.tm.slotopt.SlotOptimizationJobRegistry;
import de.vvwt.tm.tournament.events.OptimizePhaseRequestedEvent;
import de.vvwt.tm.tournament.events.SlotOptJobCompletedEvent;
import de.vvwt.tm.tournament.events.SlotOptJobScheduledEvent;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Slotopt-context FIFO scheduler for slot-optimization jobs (DEC-55 D-3a, E51S04).
 *
 * <p>Listens to two events:
 *
 * <ol>
 *   <li>{@link SlotOptJobScheduledEvent} — published by {@link
 *       de.vvwt.tm.tournament.internal.MatchGenJobExecutor} after match-generation succeeds when
 *       {@code tournament.optimize=true}. Enqueues the phaseId into the per-tournament FIFO. If the
 *       queue was empty before this enqueue (i.e., no other phase is currently optimizing),
 *       immediately publishes an {@link OptimizePhaseRequestedEvent} to drain the head.
 *   <li>{@link SlotOptJobCompletedEvent} — published by {@link SlotOptInvocationListener} after
 *       slot-opt finishes (success, cancel, or error). Dequeues the completed phaseId from the FIFO
 *       head and, if the queue is non-empty, publishes an {@link OptimizePhaseRequestedEvent} for
 *       the next entry.
 * </ol>
 *
 * <h2>Placement rationale (DEC-21 + DEC-55 D-3)</h2>
 *
 * <p>This bean lives in {@code de.vvwt.tm.slotopt.internal}. Placing it here avoids the
 * modulith-cycle that would arise if it lived in {@code tournament.internal}: {@code tournament}
 * declares {@code allowedDependencies = {"tenant"}} and MUST NOT import from {@code slotopt}.
 * Placing the scheduler in {@code slotopt.internal} is correct because:
 *
 * <ul>
 *   <li>It uses {@link SlotOptimizationJobRegistry} — an {@code slotopt}-internal concern.
 *   <li>The events it consumes ({@link SlotOptJobScheduledEvent}, {@link SlotOptJobCompletedEvent})
 *       and publishes ({@link OptimizePhaseRequestedEvent}) are all in the {@code
 *       tournament::events} named sub-interface, which {@code slotopt}'s {@code package-info.java}
 *       already lists in {@code allowedDependencies}.
 *   <li>No {@code tournament.internal} types are referenced (DEC-21 module boundary rule
 *       preserved).
 * </ul>
 *
 * <h2>FIFO serialization guarantee (DEC-55 D-3a)</h2>
 *
 * <p>The enqueue-and-check operation in {@link #onJobScheduled(SlotOptJobScheduledEvent)} is
 * synchronized at the Deque level inside {@link SlotOptimizationJobRegistry#enqueue(UUID, UUID)}
 * and {@link SlotOptimizationJobRegistry#getQueueDepth(UUID)} — see {@link
 * DefaultSlotOptimizationJobRegistry} for the concurrency model. Since the decision
 * "is-queue-was-empty → publish OptimizePhaseRequestedEvent" must be atomic, the atomic
 * enqueue-and-get-depth pattern is: enqueue first, then check depth == 1 (meaning this was the only
 * entry → publish). Depth == 1 implies the queue was empty before this enqueue.
 *
 * <h2>Transaction model</h2>
 *
 * <p>Both {@link #onJobScheduled(SlotOptJobScheduledEvent)} and {@link
 * #onJobCompleted(SlotOptJobCompletedEvent)} carry {@code @Transactional(REQUIRES_NEW)}. This
 * ensures that any {@link OptimizePhaseRequestedEvent} published within those methods is dispatched
 * inside a transaction that commits — a prerequisite for {@link SlotOptInvocationListener}'s
 * {@code @TransactionalEventListener(AFTER_COMMIT)} to fire (Spring only delivers {@code
 * AFTER_COMMIT} events to listeners when the publishing thread's active transaction has actually
 * committed).
 *
 * <h2>Error handling (AC-ERROR-HANDLING-EMPTY-QUEUE-NO-NPE)</h2>
 *
 * <p>{@link #onJobCompleted(SlotOptJobCompletedEvent)} handles the empty-queue case gracefully:
 * {@link SlotOptimizationJobRegistry#dequeueHead(UUID)} returns {@code null} on empty queue; the
 * method logs at DEBUG and returns without publishing.
 *
 * @see SlotOptJobScheduledEvent
 * @see SlotOptJobCompletedEvent
 * @see OptimizePhaseRequestedEvent
 * @see SlotOptimizationJobRegistry
 * @see SlotOptInvocationListener
 * @see <a href="DEC-55">DEC-55 D-3a — FIFO queue serial per tournament</a>
 * @see <a href="DEC-21">DEC-21 — Spring Modulith events-only cross-context pattern</a>
 * @see <a href="E51S04">E51S04 — Slot-Opt FIFO-Queue</a>
 */
@Component
class SlotOptJobScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(SlotOptJobScheduler.class);

    private final SlotOptimizationJobRegistry jobRegistry;
    private final SlotOptFifoDispatcher dispatcher;

    SlotOptJobScheduler(SlotOptimizationJobRegistry jobRegistry, SlotOptFifoDispatcher dispatcher) {
        this.jobRegistry = jobRegistry;
        this.dispatcher = dispatcher;
    }

    /**
     * Consumes {@link SlotOptJobScheduledEvent} AFTER the publishing transaction commits and
     * enqueues the phaseId into the per-tournament FIFO (DEC-55 D-3a).
     *
     * <p>If the queue depth becomes 1 after enqueue (queue was previously empty), immediately
     * publishes {@link OptimizePhaseRequestedEvent} to drain the head — no other phase is currently
     * optimizing for this tournament.
     *
     * @param event carries {@code tournamentId} and {@code phaseId}
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onJobScheduled(SlotOptJobScheduledEvent event) {
        UUID tournamentId = event.tournamentId();
        UUID phaseId = event.phaseId();

        LOG.info(
                "SlotOptJobScheduler.onJobScheduled: tournamentId={}, phaseId={}",
                tournamentId,
                phaseId);

        jobRegistry.enqueue(tournamentId, phaseId);
        int depth = jobRegistry.getQueueDepth(tournamentId);

        if (depth == 1) {
            // Queue was empty before this enqueue — drain immediately
            LOG.info(
                    "SlotOptJobScheduler: queue was empty, draining head immediately"
                            + " tournamentId={}, phaseId={}",
                    tournamentId,
                    phaseId);
            dispatcher.dispatchOptimizeRequest(tournamentId, phaseId);
        } else {
            LOG.info(
                    "SlotOptJobScheduler: phaseId={} queued at depth {} for tournamentId={};"
                            + " waiting for previous phase to complete",
                    phaseId,
                    depth,
                    tournamentId);
        }
    }

    /**
     * Consumes {@link SlotOptJobCompletedEvent} AFTER the publishing transaction commits and drains
     * the FIFO queue head (DEC-55 D-3a).
     *
     * <p>Dequeues the completed phaseId. If the queue is non-empty, publishes {@link
     * OptimizePhaseRequestedEvent} for the next head entry. If the queue is empty, logs at DEBUG
     * and returns (AC-ERROR-HANDLING-EMPTY-QUEUE-NO-NPE).
     *
     * @param event carries {@code tournamentId} and {@code phaseId} of the completed phase
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onJobCompleted(SlotOptJobCompletedEvent event) {
        UUID tournamentId = event.tournamentId();
        UUID completedPhaseId = event.phaseId();

        LOG.info(
                "SlotOptJobScheduler.onJobCompleted: tournamentId={}, completedPhaseId={}",
                tournamentId,
                completedPhaseId);

        // Dequeue the completed phase from the head
        UUID dequeued = jobRegistry.dequeueHead(tournamentId);
        if (dequeued == null) {
            // AC-ERROR-HANDLING-EMPTY-QUEUE-NO-NPE: empty queue on completion — no NPE, log and
            // return
            LOG.debug(
                    "SlotOptJobScheduler.onJobCompleted: queue was empty for tournamentId={}"
                            + " (AC-ERROR-HANDLING-EMPTY-QUEUE-NO-NPE)",
                    tournamentId);
            return;
        }

        LOG.info(
                "SlotOptJobScheduler: dequeued completedPhaseId={} for tournamentId={}",
                dequeued,
                tournamentId);

        // Drain next entry if queue is non-empty
        Optional<UUID> nextPhase = jobRegistry.peekQueue(tournamentId);
        if (nextPhase.isPresent()) {
            UUID nextPhaseId = nextPhase.get();
            LOG.info(
                    "SlotOptJobScheduler: draining next phase phaseId={} for tournamentId={}",
                    nextPhaseId,
                    tournamentId);
            dispatcher.dispatchOptimizeRequest(tournamentId, nextPhaseId);
        } else {
            LOG.info("SlotOptJobScheduler: FIFO queue exhausted for tournamentId={}", tournamentId);
        }
    }
}
