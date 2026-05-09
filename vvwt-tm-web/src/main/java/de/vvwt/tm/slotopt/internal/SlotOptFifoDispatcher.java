package de.vvwt.tm.slotopt.internal;

import de.vvwt.tm.tournament.events.OptimizePhaseRequestedEvent;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional helper that publishes {@link OptimizePhaseRequestedEvent} within a {@code
 * REQUIRES_NEW} transaction (DEC-55 D-3a, E51S04).
 *
 * <p>This bean exists to solve a Spring AOP limitation: {@code @Transactional} annotations on
 * {@code @TransactionalEventListener} methods are NOT applied by the event-listener infrastructure
 * (which calls the target method directly, bypassing the CGLIB proxy). To ensure that {@link
 * OptimizePhaseRequestedEvent} is published within a committed transaction — a prerequisite for
 * {@link SlotOptInvocationListener}'s {@code @TransactionalEventListener(AFTER_COMMIT)} to fire —
 * the publish call is delegated to this {@code @Transactional(REQUIRES_NEW)} service method, which
 * IS invoked through the AOP proxy.
 *
 * <h2>Pattern rationale</h2>
 *
 * <p>This follows the same pattern used by {@link
 * de.vvwt.tm.tournament.internal.DefaultMatchGenJobListener} → {@link
 * de.vvwt.tm.tournament.internal.DefaultMatchGenJobExecutor}: the event listener itself is not
 * {@code @Transactional}; instead it delegates to a separate {@code @Transactional(REQUIRES_NEW)}
 * component. The delegated method runs inside a Spring-managed TX, so any
 * {@code @TransactionalEventListener(AFTER_COMMIT)} subscriber fires after that TX commits.
 *
 * @see SlotOptJobScheduler
 * @see SlotOptInvocationListener
 * @see <a href="DEC-55">DEC-55 D-3a — FIFO queue serial per tournament</a>
 * @see <a href="E51S04">E51S04 — Slot-Opt FIFO-Queue</a>
 */
@Component
class SlotOptFifoDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(SlotOptFifoDispatcher.class);

    private final ApplicationEventPublisher eventPublisher;

    SlotOptFifoDispatcher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * Publishes {@link OptimizePhaseRequestedEvent} within a new transaction, so that {@link
     * SlotOptInvocationListener}'s {@code @TransactionalEventListener(AFTER_COMMIT)} fires after
     * this TX commits.
     *
     * @param tournamentId the tournament owning the phase
     * @param phaseId the phase to optimize
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dispatchOptimizeRequest(UUID tournamentId, UUID phaseId) {
        LOG.debug(
                "SlotOptFifoDispatcher.dispatchOptimizeRequest: publishing"
                        + " OptimizePhaseRequestedEvent tournamentId={}, phaseId={}",
                tournamentId,
                phaseId);
        eventPublisher.publishEvent(new OptimizePhaseRequestedEvent(tournamentId, phaseId));
    }
}
