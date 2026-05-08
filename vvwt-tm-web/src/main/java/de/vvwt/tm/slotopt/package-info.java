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
 * <p>The {@code slotopt} context depends on {@code tournament} and {@code tournament::events}:
 *
 * <ul>
 *   <li>{@code tournament} — {@link de.vvwt.tm.tournament.Match}, {@link
 *       de.vvwt.tm.tournament.MatchRepository}, {@link de.vvwt.tm.tournament.PhaseRepository},
 *       {@link de.vvwt.tm.tournament.TeamAvatar}, {@link
 *       de.vvwt.tm.tournament.TeamAvatarRepository}, {@link
 *       de.vvwt.tm.tournament.TournamentRepository}, {@link de.vvwt.tm.tournament.Phase} — used by
 *       pre-existing {@code slotopt} classes + new {@link
 *       de.vvwt.tm.slotopt.internal.SlotOptInvocationListener} (E51S04).
 *   <li>{@code tournament::events} — {@link
 *       de.vvwt.tm.tournament.events.OptimizePhaseRequestedEvent} consumed by {@link
 *       de.vvwt.tm.slotopt.internal.SlotOptInvocationListener} (E51S04, DEC-55 D-3a); {@link
 *       de.vvwt.tm.tournament.events.SlotOptJobCompletedEvent} published by same listener; {@link
 *       de.vvwt.tm.tournament.events.SlotOptJobScheduledEvent} consumed by {@link
 *       de.vvwt.tm.slotopt.internal.SlotOptJobScheduler} (E51S04) — placed in {@code
 *       slotopt.internal} to avoid a modulith cycle ({@code tournament} declares {@code
 *       allowedDependencies = {"tenant"}} and must not import from {@code slotopt}). No {@code
 *       tournament::exceptions} sub-package is referenced (verified empirically at E27S01 delivery
 *       — OMIT per DEC-40 per-entry-justification mandate).
 * </ul>
 *
 * <p>The canonical {@link SlotOptimizationClient} entry point from E27S01 forward is {@link
 * de.vvwt.tm.slotopt.internal.RoutingSlotOptimizationClient}. See DEC-49 for the routing rule
 * governing N-based leg selection (Legs 2/3 land in E27S02/S03).
 *
 * <p>Authorizing decisions: DEC-21 (Spring Modulith layout), DEC-22 (TDD Iron Law), DEC-40
 * (allowedDependencies per-entry justification), DEC-49 (routing rule + canonical entry point),
 * DEC-55 D-3a (FIFO queue; E51S04 adds {@code tournament::events} dependency).
 *
 * @since E27S01
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {"tournament", "tournament::events"})
package de.vvwt.tm.slotopt;
