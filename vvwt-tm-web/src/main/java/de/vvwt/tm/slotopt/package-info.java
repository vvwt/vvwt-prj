/**
 * Public API of the {@code slotopt} bounded context.
 *
 * <p>This package is the contract surface for the {@code slotopt} Spring Modulith module. Types
 * declared here ({@link de.vvwt.tm.slotopt.SlotOptimizationClient}, {@link
 * de.vvwt.tm.slotopt.MappingResult}, etc.) are consumable by other Modulith modules; types in
 * {@code de.vvwt.tm.slotopt.internal} are implementation details and MUST NOT be accessed by any
 * other module.
 *
 * <h2>Allowed dependencies (DEC-21, DEC-49)</h2>
 *
 * <p>The {@code slotopt} context depends on {@code tournament}:
 *
 * <ul>
 *   <li>{@code tournament} — {@link de.vvwt.tm.tournament.Match}, {@link
 *       de.vvwt.tm.tournament.MatchRepository}, {@link de.vvwt.tm.tournament.PhaseRepository},
 *       {@link de.vvwt.tm.tournament.TeamAvatar}, {@link
 *       de.vvwt.tm.tournament.TeamAvatarRepository}, {@link
 *       de.vvwt.tm.tournament.TournamentRepository}, {@link de.vvwt.tm.tournament.Phase} — used by
 *       pre-existing {@code slotopt} classes.
 * </ul>
 *
 * <p>E55S06 (DEC-64 D-5): {@code tournament::events} dependency removed. {@link
 * de.vvwt.tm.slotopt.internal.SlotOptInvocationListener}, {@link
 * de.vvwt.tm.slotopt.internal.SlotOptJobScheduler}, and {@link
 * de.vvwt.tm.slotopt.internal.SlotOptFifoDispatcher} are deleted as part of the event-driven
 * pipeline removal. The events {@code OptimizePhaseRequestedEvent}, {@code
 * SlotOptJobScheduledEvent}, and {@code SlotOptJobCompletedEvent} are also deleted.
 *
 * <p>The canonical {@link SlotOptimizationClient} entry point from E27S01 forward is {@link
 * de.vvwt.tm.slotopt.internal.RoutingSlotOptimizationClient}. See DEC-49 for the routing rule
 * governing N-based leg selection (Legs 2/3 land in E27S02/S03).
 *
 * <p>Authorizing decisions: DEC-21 (Spring Modulith layout), DEC-22 (TDD Iron Law), DEC-40
 * (allowedDependencies per-entry justification), DEC-49 (routing rule + canonical entry point),
 * DEC-64 D-5 (event pipeline deleted; {@code tournament::events} dependency removed, E55S06).
 *
 * @since E27S01
 * @updated E55S06 (removed {@code tournament::events} from allowedDependencies — DEC-64 D-5)
 */
@org.springframework.modulith.ApplicationModule(allowedDependencies = {"tournament"})
package de.vvwt.tm.slotopt;
