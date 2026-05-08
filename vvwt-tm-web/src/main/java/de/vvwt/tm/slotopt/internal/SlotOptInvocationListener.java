package de.vvwt.tm.slotopt.internal;

import de.vvwt.tm.slotopt.SlotOptimizationClient;
import de.vvwt.tm.slotopt.SlotOptimizationJobRegistry;
import de.vvwt.tm.tournament.Phase;
import de.vvwt.tm.tournament.PhaseRepository;
import de.vvwt.tm.tournament.Tournament;
import de.vvwt.tm.tournament.TournamentRepository;
import de.vvwt.tm.tournament.events.OptimizePhaseRequestedEvent;
import de.vvwt.tm.tournament.events.SlotOptJobCompletedEvent;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Slotopt-context invocation listener that runs slot-optimization for a phase (DEC-55 D-3a,
 * E51S04).
 *
 * <p>Consumes {@link OptimizePhaseRequestedEvent} (published by {@link
 * de.vvwt.tm.tournament.internal.SlotOptJobScheduler}), invokes {@link
 * SlotOptimizationClient#optimize(UUID)} synchronously within its transaction, and on completion
 * (success or error) publishes {@link SlotOptJobCompletedEvent} to drain the next FIFO entry.
 *
 * <h2>Transaction model (DEC-37 Clause B)</h2>
 *
 * <ol>
 *   <li>{@code @TransactionalEventListener(phase = AFTER_COMMIT)} — fires only after the publishing
 *       transaction commits. {@link OptimizePhaseRequestedEvent} is always published inside a
 *       committed {@code REQUIRES_NEW} transaction via {@link SlotOptFifoDispatcher}, ensuring this
 *       listener reliably fires.
 *   <li>{@code @Async} — runs in the async executor thread so the event-publisher thread is not
 *       blocked.
 *   <li>{@code @Transactional(REQUIRES_NEW)} — the optimization runs inside its own new
 *       transaction. DEC-37 Clause B: the per-tournament row-lock on {@code tournament} is the
 *       FIRST read.
 * </ol>
 *
 * <h2>last_job_state transitions (AC-TEST-LAST-JOB-STATE-TRANSITIONS-RED)</h2>
 *
 * <ol>
 *   <li>Before calling {@link SlotOptimizationClient#optimize(UUID)}: set {@code
 *       phase.last_job_state='slot_opt_running'}.
 *   <li>On success: set {@code phase.optimized=true}, {@code phase.last_job_state='idle'}.
 *   <li>On exception: set {@code phase.last_job_state='failed'} (optimized remains false).
 * </ol>
 *
 * <h2>Error handling (AC-ERROR-HANDLING-LISTENER-EXCEPTION-NO-QUEUE-LOSS)</h2>
 *
 * <p>If {@link SlotOptimizationClient#optimize(UUID)} throws, this listener:
 *
 * <ol>
 *   <li>Logs at ERROR level with full context.
 *   <li>Sets {@code phase.last_job_state='failed'}.
 *   <li>Publishes {@link SlotOptJobCompletedEvent} UNCONDITIONALLY — failure of one phase does NOT
 *       block the FIFO queue.
 * </ol>
 *
 * <h2>Placement (DEC-21 + AC-IMPL-INVOCATION-LISTENER-IN-SLOTOPT-INTERNAL)</h2>
 *
 * <p>Lives in {@code de.vvwt.tm.slotopt.internal}. Consumes events from the {@code
 * tournament::events} named interface (allowed by {@code de.vvwt.tm.slotopt.package-info.java
 * allowedDependencies = {"tournament", "tournament::events"}} — E51S04 added {@code
 * tournament::events}).
 *
 * @see OptimizePhaseRequestedEvent
 * @see SlotOptJobCompletedEvent
 * @see SlotOptJobScheduler
 * @see SlotOptimizationClient
 * @see <a href="DEC-55">DEC-55 D-3a — FIFO queue serial per tournament</a>
 * @see <a href="DEC-37">DEC-37 Clause B — pessimistic row-lock as first read</a>
 * @see <a href="DEC-49">DEC-49 D-11a — Best-So-Far semantics on cancel</a>
 * @see <a href="E51S04">E51S04 — Slot-Opt FIFO-Queue</a>
 */
@Component
class SlotOptInvocationListener {

    private static final Logger LOG = LoggerFactory.getLogger(SlotOptInvocationListener.class);

    private final TournamentRepository tournamentRepository;
    private final PhaseRepository phaseRepository;
    private final SlotOptimizationClient slotOptClient;
    private final SlotOptimizationJobRegistry jobRegistry;
    private final ApplicationEventPublisher eventPublisher;

    SlotOptInvocationListener(
            TournamentRepository tournamentRepository,
            PhaseRepository phaseRepository,
            SlotOptimizationClient slotOptClient,
            SlotOptimizationJobRegistry jobRegistry,
            ApplicationEventPublisher eventPublisher) {
        this.tournamentRepository = tournamentRepository;
        this.phaseRepository = phaseRepository;
        this.slotOptClient = slotOptClient;
        this.jobRegistry = jobRegistry;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Consumes {@link OptimizePhaseRequestedEvent} AFTER the publishing transaction commits,
     * invokes slot-optimization in a new transaction, and publishes {@link
     * SlotOptJobCompletedEvent} unconditionally.
     *
     * @param event carries {@code tournamentId} and {@code phaseId}
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onOptimizePhaseRequested(OptimizePhaseRequestedEvent event) {
        UUID tournamentId = event.tournamentId();
        UUID phaseId = event.phaseId();

        LOG.info(
                "SlotOptInvocationListener: starting slot-opt tournamentId={}, phaseId={}",
                tournamentId,
                phaseId);

        // Step 1: DEC-37 Clause B — acquire per-tournament row-lock as the FIRST read
        // (AC-GOVERNANCE-DEC-37-LOCK-PRESERVED)
        @SuppressWarnings("unused") // lock acquired for serialization; value not used further
        Tournament tournament = tournamentRepository.findByIdForUpdate(tournamentId);

        // Step 2: Load phase
        Phase phase =
                phaseRepository
                        .findById(phaseId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Phase not found for slot-opt: " + phaseId));

        // Step 3: Set last_job_state = 'slot_opt_running' BEFORE invocation
        // (AC-TEST-LAST-JOB-STATE-TRANSITIONS-RED)
        phase.setLastJobState("slot_opt_running");
        phaseRepository.save(phase);

        try {
            // Step 4: Invoke slot-optimization (DEC-49 D-3 routing semantics preserved)
            slotOptClient.optimize(phaseId);

            // Step 5: Success — flip phase.optimized=true, set last_job_state='idle'
            // (AC-TEST-PHASE-OPTIMIZED-FLIP-ON-SUCCESS-RED)
            phase.setOptimized(true);
            phase.setLastJobState("idle");
            phaseRepository.save(phase);

            LOG.info(
                    "SlotOptInvocationListener: SUCCESS tournamentId={}, phaseId={}",
                    tournamentId,
                    phaseId);

        } catch (Exception ex) {
            // AC-ERROR-HANDLING-LISTENER-EXCEPTION-NO-QUEUE-LOSS: failure must NOT block FIFO queue
            LOG.error(
                    "SlotOptInvocationListener: FAILED tournamentId={}, phaseId={}"
                            + " — setting last_job_state='failed'; queue drain continues",
                    tournamentId,
                    phaseId,
                    ex);

            // Set last_job_state = 'failed' — optimized remains false
            phase.setLastJobState("failed");
            phaseRepository.save(phase);

        } finally {
            // Step 6: Publish SlotOptJobCompletedEvent UNCONDITIONALLY
            // (AC-ERROR-HANDLING-LISTENER-EXCEPTION-NO-QUEUE-LOSS — failure does NOT block queue)
            eventPublisher.publishEvent(new SlotOptJobCompletedEvent(tournamentId, phaseId));
            LOG.info(
                    "SlotOptInvocationListener: published SlotOptJobCompletedEvent"
                            + " tournamentId={}, phaseId={}",
                    tournamentId,
                    phaseId);
        }
    }
}
