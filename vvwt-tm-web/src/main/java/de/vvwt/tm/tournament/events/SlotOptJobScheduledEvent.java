package de.vvwt.tm.tournament.events;

import java.util.UUID;

/**
 * Spring ApplicationEvent signalling that Slot-Optimization should be enqueued for a phase (DEC-55
 * D-3, E51S03).
 *
 * <p>Published by {@link de.vvwt.tm.tournament.internal.MatchGenJobListener} after successful
 * match-generation IF {@code tournament.optimize=true}. Consumed by {@code SlotOptJobScheduler}
 * (tournament context, E51S04) which enqueues the phase into the per-tournament FIFO queue (DEC-55
 * D-3a). The listener for this event is wired in E51S04 — this story only publishes the event "into
 * the void".
 *
 * <h2>Tenant propagation</h2>
 *
 * <p>The tenant context is automatically propagated from the publishing thread to the executor
 * thread via the {@code TenantContextTaskDecorator} registered on the {@code taskExecutor} bean
 * (configured in {@code MatchGenAsyncConfig}). Downstream async consumers (E51S04+) must ensure
 * their executor also decorates with tenant propagation.
 *
 * <h2>Modulith-cycle avoidance (DEC-21 + DEC-55 D-3)</h2>
 *
 * <p>This event lives in the {@code tournament} context's public events package. Consuming
 * listeners may reside in any context but MUST NOT import from {@code tournament.internal}. The
 * {@code slotopt-integration} context consumes this event (E51S04) without adding a new
 * compile-time dependency edge.
 *
 * <h2>Immutability</h2>
 *
 * <p>Java record — all fields are effectively final. No setter access.
 *
 * @param tournamentId the UUID of the tournament for which slot-optimization should run
 * @param phaseId the UUID of the phase for which slot-optimization should run
 * @see de.vvwt.tm.tournament.internal.MatchGenJobListener
 * @see <a href="DEC-55">DEC-55 D-3 — Background-Job-Pipeline (events-only)</a>
 * @see <a href="DEC-21">DEC-21 — Spring Modulith package layout + events cross-context pattern</a>
 * @see <a href="E51S04">E51S04 — Slot-Opt FIFO-Queue + SlotOptJobScheduledEvent listener</a>
 */
public record SlotOptJobScheduledEvent(UUID tournamentId, UUID phaseId) {}
