package de.vvwt.tm.tournament.events;

import java.util.UUID;

/**
 * Spring ApplicationEvent requesting slot-optimization execution for a specific phase (DEC-55 D-3a,
 * E51S04).
 *
 * <p>Published by {@link de.vvwt.tm.tournament.internal.SlotOptJobScheduler} when:
 *
 * <ol>
 *   <li>A {@link SlotOptJobScheduledEvent} is consumed and the FIFO queue was empty → immediate
 *       drain: publish this event for the single enqueued phase.
 *   <li>A {@link SlotOptJobCompletedEvent} is consumed and the FIFO queue is non-empty → drain the
 *       next head element.
 * </ol>
 *
 * <p>Consumed by {@link de.vvwt.tm.slotopt.internal.SlotOptInvocationListener} (slotopt context)
 * which invokes {@link de.vvwt.tm.slotopt.SlotOptimizationClient#optimize(UUID)} synchronously.
 *
 * <h2>Modulith-cycle avoidance (DEC-21 + DEC-55 D-3)</h2>
 *
 * <p>This event lives in the {@code tournament} context's public {@code events} named-interface
 * package. Consuming listeners in the {@code slotopt} context receive this event via Spring's
 * {@code ApplicationEvent} mechanism — no new compile-time edge from {@code slotopt} to {@code
 * tournament} is introduced.
 *
 * <h2>Immutability</h2>
 *
 * <p>Java record — all fields are effectively final.
 *
 * @param tournamentId the UUID of the tournament owning the slot-opt FIFO queue (DEC-55 D-3a)
 * @param phaseId the UUID of the phase whose slot-optimization should now run
 * @see de.vvwt.tm.tournament.internal.SlotOptJobScheduler
 * @see de.vvwt.tm.slotopt.internal.SlotOptInvocationListener
 * @see <a href="DEC-55">DEC-55 D-3a — FIFO-queue serial per tournament</a>
 * @see <a href="DEC-21">DEC-21 — Spring Modulith events-only cross-context pattern</a>
 * @see <a href="E51S04">E51S04 — Slot-Opt FIFO-Queue</a>
 */
public record OptimizePhaseRequestedEvent(UUID tournamentId, UUID phaseId) {}
