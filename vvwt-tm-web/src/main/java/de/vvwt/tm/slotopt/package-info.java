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
 * <p>The {@code slotopt} context depends on:
 *
 * <ul>
 *   <li>{@code tournament} — {@link de.vvwt.tm.tournament.Match}, {@link
 *       de.vvwt.tm.tournament.MatchRepository}, {@link de.vvwt.tm.tournament.PhaseRepository},
 *       {@link de.vvwt.tm.tournament.TeamAvatar}, {@link
 *       de.vvwt.tm.tournament.TeamAvatarRepository}, {@link
 *       de.vvwt.tm.tournament.TournamentRepository}, {@link de.vvwt.tm.tournament.Phase} — used by
 *       pre-existing {@code slotopt} classes.
 *   <li>{@code tournament::events} — {@link de.vvwt.tm.tournament.events.PhaseStatusChangedEvent} —
 *       observed by {@link de.vvwt.tm.slotopt.internal.DefaultHostActivityProbe} to track whether
 *       any phase is ACTIVE (live-scoring running). This is the permitted DEC-64/C-5 in-process
 *       host-coupling channel for the embedded worker (E63S04). Unlike the E55S06 removal of
 *       slot-opt pipeline events, this dependency is for READ-ONLY observation of host live-scoring
 *       state — a distinct concern.
 * </ul>
 *
 * <p>E55S06 (DEC-64 D-5): the slot-opt pipeline events ({@code OptimizePhaseRequestedEvent}, {@code
 * SlotOptJobScheduledEvent}, {@code SlotOptJobCompletedEvent}) and their listener beans ({@code
 * SlotOptInvocationListener}, {@code SlotOptJobScheduler}, {@code SlotOptFifoDispatcher}) were
 * deleted. The {@code tournament::events} dependency was removed at that time. E63S04 re-adds it
 * for a different purpose: read-only host-activity observation.
 *
 * <p>The canonical {@link SlotOptimizationClient} entry point from E27S01 forward is {@link
 * de.vvwt.tm.slotopt.internal.RoutingSlotOptimizationClient}. See DEC-49 for the routing rule
 * governing N-based leg selection (Legs 2/3 land in E27S02/S03).
 *
 * <p>Authorizing decisions: DEC-21 (Spring Modulith layout), DEC-22 (TDD Iron Law), DEC-40
 * (allowedDependencies per-entry justification), DEC-49 (routing rule + canonical entry point),
 * DEC-64 D-5 / DEC-64 C-5 (host-coupling permitted via HostActivityProbe — E63S04).
 *
 * @since E27S01
 * @updated E63S04 (re-added {@code tournament::events} for host-activity probe — DEC-64 C-5)
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"tournament", "tournament::events"})
package de.vvwt.tm.slotopt;
