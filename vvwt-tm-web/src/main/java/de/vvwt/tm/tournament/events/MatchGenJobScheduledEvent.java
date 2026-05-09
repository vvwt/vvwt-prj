package de.vvwt.tm.tournament.events;

import java.util.UUID;

/**
 * Spring ApplicationEvent signalling that Match-Generation should run asynchronously for a phase
 * (DEC-55 D-3, E51S03).
 *
 * <p>Published by {@link de.vvwt.tm.tournament.internal.DefaultDraftService#apply} immediately
 * after structural {@link de.vvwt.tm.tournament.TeamAvatar} placeholders have been persisted for
 * the phase (E51S02). Consumed by {@link de.vvwt.tm.tournament.internal.DefaultMatchGenJobListener}
 * via {@code @TransactionalEventListener(phase = AFTER_COMMIT)} + {@code @Async}: the listener
 * invokes {@link de.vvwt.tm.tournament.internal.DefaultPhasePreparationService#generateMatches}
 * after the apply-transaction commits (so avatars are fully visible to the listener's new
 * transaction).
 *
 * <h2>Tenant propagation</h2>
 *
 * <p>The tenant context is automatically propagated from the publishing thread to the executor
 * thread via the {@code TenantContextTaskDecorator} registered on the {@code taskExecutor} bean
 * (configured in {@code MatchGenAsyncConfig}). No tenant ID is carried in the event itself — the
 * decorator captures it at task-submission time (when the {@code @Async} proxy dispatches the
 * listener to the executor), while the publishing thread still has the tenant bound.
 *
 * <h2>Modulith-cycle avoidance (DEC-21 + DEC-55 D-3)</h2>
 *
 * <p>This event lives in the {@code tournament} context's public events package. The {@code
 * slotopt-integration} context may listen to it but does NOT import from {@code
 * tournament.internal}. The existing {@code slotopt → tournament} allowedDependency edge is
 * preserved; NO new {@code tournament → slotopt} edge is introduced. Cross-context communication
 * travels exclusively via Spring ApplicationEvent.
 *
 * <h2>Immutability</h2>
 *
 * <p>Java record — all fields are effectively final. No setter access.
 *
 * @param tournamentId the UUID of the tournament whose apply() published this event
 * @param phaseId the UUID of the phase for which match-generation should run
 * @see de.vvwt.tm.tournament.internal.DefaultMatchGenJobListener
 * @see <a href="DEC-55">DEC-55 D-3 — Background-Job-Pipeline (events-only)</a>
 * @see <a href="DEC-21">DEC-21 — Spring Modulith package layout + events cross-context pattern</a>
 * @see <a href="E51S03">E51S03 — Background-Job-Pipeline foundation</a>
 */
public record MatchGenJobScheduledEvent(UUID tournamentId, UUID phaseId) {}
