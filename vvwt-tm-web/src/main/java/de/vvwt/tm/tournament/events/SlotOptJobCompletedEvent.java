package de.vvwt.tm.tournament.events;

import java.util.UUID;

/**
 * Spring ApplicationEvent signalling that slot-optimization for a phase has completed (success,
 * cancel, or failure) — DEC-55 D-3a, E51S04.
 *
 * <p>Published by {@link de.vvwt.tm.slotopt.internal.SlotOptInvocationListener} after the
 * slot-optimization job finishes for a phase, regardless of outcome (success, error, or cancel).
 * Publishing is unconditional on the error path
 * (AC-ERROR-HANDLING-LISTENER-EXCEPTION-NO-QUEUE-LOSS) to ensure the per-tournament FIFO queue
 * always drains.
 *
 * <p>Consumed by {@link de.vvwt.tm.tournament.internal.SlotOptJobScheduler} (tournament context)
 * which dequeues the completed phaseId and drains the next entry if the queue is non-empty.
 *
 * <h2>Modulith-cycle avoidance (DEC-21 + DEC-55 D-3)</h2>
 *
 * <p>This event lives in the {@code tournament} context's public {@code events} named-interface
 * package. It is published by the {@code slotopt} context and consumed by the {@code tournament}
 * context — the existing {@code slotopt → tournament} compile-time edge (per {@code
 * de.vvwt.tm.slotopt.package-info.java allowedDependencies = {"tournament"}}) covers this. No NEW
 * compile-time edge is introduced.
 *
 * <h2>Immutability</h2>
 *
 * <p>Java record — all fields are effectively final.
 *
 * @param tournamentId the UUID of the tournament owning the slot-opt FIFO queue (DEC-55 D-3a)
 * @param phaseId the UUID of the phase whose slot-optimization has just finished
 * @see de.vvwt.tm.slotopt.internal.SlotOptInvocationListener
 * @see de.vvwt.tm.tournament.internal.SlotOptJobScheduler
 * @see <a href="DEC-55">DEC-55 D-3a — FIFO-queue serial per tournament</a>
 * @see <a href="E51S04">E51S04 — Slot-Opt FIFO-Queue</a>
 */
public record SlotOptJobCompletedEvent(UUID tournamentId, UUID phaseId) {}
